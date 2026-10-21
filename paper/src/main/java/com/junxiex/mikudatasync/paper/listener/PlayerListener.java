package com.junxiex.mikudatasync.paper.listener;

import com.junxiex.mikudatasync.common.NackMessage;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.paper.ApplyService;
import com.junxiex.mikudatasync.paper.CaptureService;
import com.junxiex.mikudatasync.paper.HandoffManager;
import com.junxiex.mikudatasync.paper.IpcClient;
import com.junxiex.mikudatasync.paper.MikuDataSyncPaper;
import com.junxiex.mikudatasync.paper.NmsBridge;
import com.junxiex.mikudatasync.paper.PaperConfig;
import com.junxiex.mikudatasync.paper.PendingSnapshots;
import com.junxiex.mikudatasync.paper.PluginSchedulers;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Pre-login fetch -> join apply -> ack, and quit/periodic capture -> PUT. Applying before the
 * player can interact is what removes the "player already acting while data is stale" window.
 */
public final class PlayerListener implements Listener {

    private final MikuDataSyncPaper plugin;
    private final PaperConfig config;
    private final IpcClient ipcClient;
    private final CaptureService captureService;
    private final ApplyService applyService;
    private final PendingSnapshots pendingSnapshots;
    private final HandoffManager handoffManager;

    public PlayerListener(MikuDataSyncPaper plugin, PaperConfig config, IpcClient ipcClient,
                          CaptureService captureService, ApplyService applyService,
                          PendingSnapshots pendingSnapshots, HandoffManager handoffManager) {
        this.plugin = plugin;
        this.config = config;
        this.ipcClient = ipcClient;
        this.captureService = captureService;
        this.applyService = applyService;
        this.pendingSnapshots = pendingSnapshots;
        this.handoffManager = handoffManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (ipcClient.state() != IpcClient.State.CONNECTED) {
            unavailable(event, "proxy IPC is not connected");
            return;
        }
        UUID uuid = event.getUniqueId();
        try {
            SnapshotMessage message = ipcClient
                    .getSnapshot(uuid, config.preloginFetchTimeoutMs())
                    .get(config.preloginFetchTimeoutMs() + 500L, TimeUnit.MILLISECONDS);
            if (message == null) {
                return;
            }
            SnapshotEnvelope envelope = SnapshotEnvelope.decode(message.envelope());
            if (envelope.dataVersion() != NmsBridge.dataVersion()) {
                unavailable(event, "data version mismatch: snapshot=" + envelope.dataVersion()
                        + ", local=" + NmsBridge.dataVersion());
                return;
            }
            pendingSnapshots.put(uuid, new PendingSnapshots.Pending(envelope, message.seq(), System.currentTimeMillis()));
        } catch (Exception e) {
            unavailable(event, "failed to fetch snapshot: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PendingSnapshots.Pending pending = pendingSnapshots.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        try {
            applyService.apply(player, pending.envelope());
            PluginSchedulers.runDelayed(plugin, player, () -> {
                try {
                    applyService.applyCursor(player, pending.envelope());
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to restore cursor item: " + e.getMessage());
                }
            }, 1L);
            ipcClient.ack(player.getUniqueId(), pending.seq());
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause instanceof java.lang.reflect.InvocationTargetException) {
                cause = cause.getCause();
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to apply snapshot for " + player.getName(), cause);
            ipcClient.nack(new NackMessage(player.getUniqueId(), pending.seq(), "apply_failed",
                    String.valueOf(cause.getMessage())));
            player.kick(Component.text("跨服数据应用失败，请重新连接"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        pendingSnapshots.remove(uuid);
        captureService.forget(uuid);
        if (handoffManager.isHandingOff(uuid)) {
            // The switch flush already persisted the final state; a quit snapshot here could
            // overwrite the new server's snapshot with stale source-side data.
            handoffManager.clear(uuid);
            return;
        }
        SnapshotMessage message = captureService.capture(player, "quit");
        if (message == null) {
            return;
        }
        PluginSchedulers.runAsync(plugin, () ->
                ipcClient.putSnapshot(uuid, message, 5000L).whenComplete((seq, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("Failed to persist quit snapshot for " + uuid + ": " + error.getMessage());
                    }
                }));
    }

    private void unavailable(AsyncPlayerPreLoginEvent event, String reason) {
        if ("continue".equalsIgnoreCase(config.onSyncUnavailable())) {
            plugin.getLogger().warning("Allowing login without synchronized data: " + reason);
            return;
        }
        plugin.getLogger().warning("Denying login: " + reason);
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "跨服数据暂不可用，请稍后再试");
    }
}
