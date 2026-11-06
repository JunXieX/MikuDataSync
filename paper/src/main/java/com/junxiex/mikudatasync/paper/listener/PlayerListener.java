package com.junxiex.mikudatasync.paper.listener;

import com.junxiex.mikudatasync.common.NackMessage;
import com.junxiex.mikudatasync.common.ProtocolException;
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
import org.bukkit.inventory.ItemStack;
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
    private final java.util.Set<UUID> skipQuitCapture = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            // Another plugin already refused this login; do not spend an IPC round trip on it.
            return;
        }
        if (ipcClient.state() != IpcClient.State.CONNECTED) {
            unavailable(event, "proxy IPC is not connected");
            return;
        }
        UUID uuid = event.getUniqueId();
        SnapshotMessage message;
        try {
            message = ipcClient.getSnapshot(uuid, config.preloginFetchTimeoutMs())
                    // request() already applies orTimeout; the outer get only guards a stuck
                    // callback and must not double the login-thread budget.
                    .get(config.preloginFetchTimeoutMs() + 100L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            unavailable(event, "failed to fetch snapshot: " + e.getMessage());
            return;
        }
        if (message == null) {
            return;
        }
        ApplyService.Prepared prepared;
        try {
            prepared = applyService.prepare(SnapshotEnvelope.decode(message.envelope()));
        } catch (Exception e) {
            // The stored snapshot was written by another build (data or schema version) and cannot be
            // applied here. Denying the login would lock this player out of every server until the
            // journal is cleared, while the snapshot itself is unusable either way.
            plugin.getLogger().warning("Stored snapshot for " + uuid + " does not fit this server ("
                    + e.getMessage() + "); the player enters with local data");
            pendingSnapshots.markUnsynced(uuid);
            return;
        }
        if (!pendingSnapshots.put(uuid,
                new PendingSnapshots.Pending(message.seq(), System.currentTimeMillis(), prepared))) {
            // Entering without the snapshot would mean playing on stale data and never ACKing.
            unavailable(event, "too many pending snapshots");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PendingSnapshots.Pending pending = pendingSnapshots.remove(player.getUniqueId());
        if (pending == null) {
            // Brand-new player: the proxy has no snapshot, so the local data stays authoritative.
            return;
        }
        try {
            applyService.apply(player, pending.prepared());
            ipcClient.ack(player.getUniqueId(), pending.seq());
        } catch (Exception e) {
            skipQuitCapture.add(player.getUniqueId());
            com.junxiex.mikudatasync.common.Metrics.recordApplyFailure();
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to apply snapshot for " + player.getName(), e);
            ipcClient.nack(new NackMessage(player.getUniqueId(), pending.seq(), "apply_failed",
                    String.valueOf(e.getMessage())));
            player.kick(Component.text("跨服数据应用失败，请重新连接"));
        }
    }

    // HIGHEST rather than MONITOR: the quit capture and any cursor restore must still run while the
    // entity is alive, otherwise an addItem can be discarded with the entity.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (skipQuitCapture.remove(uuid)) {
            pendingSnapshots.discard(uuid);
            captureService.forget(uuid);
            handoffManager.clear(uuid);
            return;
        }
        pendingSnapshots.discard(uuid);
        captureService.forget(uuid);
        if (pendingSnapshots.takeUnsynced(uuid)) {
            // This session ran on local data because its snapshot does not fit this build; capturing
            // it now would replace that snapshot with a character that may be empty here.
            handoffManager.clear(uuid);
            return;
        }
        if (handoffManager.isHandingOff(uuid)) {
            // The switch flush already persisted the final state; a quit snapshot here could
            // overwrite the new server's snapshot with stale source-side data.
            handoffManager.clear(uuid);
            return;
        }
        // Vanilla drops the menu cursor item into the world when the player disconnects, so the
        // snapshot would carry a copy while the world keeps another. Take it out of the menu first
        // and pass it in as the cursor payload, exactly like the switch path does.
        ItemStack carried = null;
        if (captureService.capturesCursor()) {
            carried = NmsBridge.getCarriedItem(player);
            if (carried != null && !carried.getType().isAir()) {
                NmsBridge.setCarriedItem(player, null);
            } else {
                carried = null;
            }
        }
        SnapshotMessage message = captureService.capture(player, "quit", carried);
        if (message == null) {
            if (carried != null) {
                // The snapshot never materialised. The menu is discarded right after quit, so the item
                // must go into the inventory (dropping the overflow) instead of back onto the cursor.
                if (player.isOnline()) {
                    java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(carried);
                    for (ItemStack remaining : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), remaining);
                    }
                } else {
                    // Already removed: dropping into the world is the only form that is guaranteed
                    // to be saved with the chunk.
                    player.getWorld().dropItemNaturally(player.getLocation(), carried);
                }
            }
            return;
        }
        // Same reason as the periodic path: the quit capture is the newest state for this player, so
        // its PUT has to be enqueued here rather than on another thread.
        ipcClient.putSnapshot(uuid, message, 5000L).whenComplete((seq, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Failed to persist quit snapshot for " + uuid + ": " + error.getMessage());
            }
        });
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
