package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.paper.command.MdsCommand;
import com.junxiex.mikudatasync.paper.listener.HandoffListener;
import com.junxiex.mikudatasync.paper.listener.PlayerListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class MikuDataSyncPaper extends JavaPlugin {

    private PaperConfig config;
    private IpcClient ipcClient;
    private CaptureService captureService;
    private ApplyService applyService;
    private PendingSnapshots pendingSnapshots;
    private HandoffManager handoffManager;
    private MapBindings mapBindings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            config = PaperConfig.load(getConfig());
        } catch (Exception e) {
            getLogger().severe("Invalid configuration: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        mapBindings = new MapBindings(getDataFolder().toPath().resolve("map-bindings.properties"), getLogger());
        NmsBridge.initialize(getLogger(), config.serverId(), mapBindings);
        ipcClient = new IpcClient(this, config);
        captureService = new CaptureService(getLogger(), config);
        applyService = new ApplyService(config);
        pendingSnapshots = new PendingSnapshots();
        handoffManager = new HandoffManager(this, ipcClient, captureService);

        PlayerListener playerListener = new PlayerListener(this, config, ipcClient, captureService, applyService, pendingSnapshots, handoffManager);
        RestoreHandler restoreHandler = new RestoreHandler(this, ipcClient, applyService);
        BackupHandler backupHandler = new BackupHandler(this, ipcClient, captureService);
        ipcClient.setFrameHandler(frame -> {
            try {
                switch (frame.opcode()) {
                    case FLUSH_REQ -> handoffManager.handleFlush(IpcPayloads.parseUuid(frame.payload()), frame.requestId());
                    case HANDOFF_ABORT -> {
                        IpcPayloads.AbortRequest abort = IpcPayloads.readAbort(frame.payload());
                        handoffManager.abort(abort.uuid(), abort.snapshotCommitted());
                    }
                    case RESTORE_APPLY -> restoreHandler.handle(frame.payload(), frame.requestId());
                    case BACKUP_REQ -> backupHandler.handle(frame.payload(), frame.requestId());
                    default -> {
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Failed to handle IPC frame " + frame.opcode() + ": " + e.getMessage());
            }
        });
        ipcClient.start();

        registerPermission();
        getServer().getPluginManager().registerEvents(playerListener, this);
        getServer().getPluginManager().registerEvents(new HandoffListener(handoffManager), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        MdsCommand.node(this, ipcClient, config, captureService),
                        "MikuDataSync administration",
                        java.util.List.of()
                ));
        if (config.debugEnabled()) {
            getLogger().warning("Debug commands are ENABLED; disable debug.enabled in production");
        }
        if (com.junxiex.mikudatasync.common.Protocol.PLACEHOLDER_SECRET.equals(config.secret())) {
            getLogger().warning("proxy.secret is still the value shipped in config.yml; set a random secret "
                    + "that matches the proxy, otherwise anyone who can reach the proxy IPC port can "
                    + "inject player data");
        }
        startPeriodicSnapshots();
        getLogger().info("MikuDataSync backend enabled for server " + config.serverId()
                + " (" + (PluginSchedulers.isFolia() ? "Folia" : "Paper") + ")");
    }

    /**
     * Registers the admin node at runtime. Without it Bukkit only answers {@code hasPermission}
     * positively for operators, so a LuckPerms-granted non-OP admin would not even see the command.
     */
    private void registerPermission() {
        try {
            org.bukkit.permissions.Permission permission =
                    new org.bukkit.permissions.Permission("mikudatasync.admin",
                            org.bukkit.permissions.PermissionDefault.OP);
            getServer().getPluginManager().addPermission(permission);
        } catch (IllegalArgumentException alreadyRegistered) {
            // Reload path: the node exists already and keeps its description.
        }
    }

    /** Removes only the node this plugin created, so a reload does not leave stale entries. */
    private void removeRegisteredPermission() {
        try {
            getServer().getPluginManager().removePermission("mikudatasync.admin");
        } catch (IllegalArgumentException notRegistered) {
            // Nothing to clean up.
        }
    }

    private void startPeriodicSnapshots() {
        // Map bindings are written in batches instead of once per map on the entity thread. This is
        // independent of snapshot scheduling, so it runs even when periodic snapshots are disabled.
        PluginSchedulers.runTimer(this, () -> {
            if (mapBindings != null) {
                mapBindings.flush();
            }
        }, 100L, 100L);
        int seconds = config.periodicSnapshotSeconds();
        if (seconds <= 0) {
            return;
        }
        long ticks = 20L * seconds;
        PluginSchedulers.runTimer(this, this::snapshotOnlinePlayers, ticks, ticks);
    }

    private void snapshotOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (handoffManager.isHandingOff(player.getUniqueId())) {
                // The switch flush already owns this player's snapshot; a periodic capture now would
                // race the final PUT and could overwrite it with a pre-flush state.
                continue;
            }
            PluginSchedulers.runOnEntity(this, player, () -> {
                SnapshotMessage message = captureService.capture(player, "periodic");
                if (message == null) {
                    return;
                }
                UUID uuid = player.getUniqueId();
                PluginSchedulers.runAsync(this, () ->
                        ipcClient.putSnapshot(uuid, message, 5000L).whenComplete((seq, error) -> {
                            if (error != null) {
                                getLogger().fine("Periodic snapshot failed for " + uuid + ": " + error.getMessage());
                            }
                        }));
            });
        }
    }

    @Override
    public void onDisable() {
        removeRegisteredPermission();
        if (mapBindings != null) {
            mapBindings.flush();
        }
        if (ipcClient == null) {
            return;
        }
        if (captureService != null) {
            flushShutdownSnapshots();
        }
        ipcClient.awaitPending(1000L);
        ipcClient.drainWrites(500L);
        ipcClient.close();
    }

    /**
     * Captures every online player for the last time. Capture reads inventory/ender chest/PDC, so it
     * must run on the owning entity/region thread (on Folia reading another region's player is not
     * allowed), and the writes are awaited once for the whole batch instead of per player: a serial
     * one-second wait per player used to add up to the watchdog limit on a full server.
     */
    private void flushShutdownSnapshots() {
        java.util.List<Player> players = new java.util.ArrayList<>(getServer().getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        if (!PluginSchedulers.isFolia()) {
            // On Paper onDisable already runs on the main thread, which is the thread that owns all
            // player state; scheduling here would post a task that never runs during shutdown.
            for (Player player : players) {
                flushOneShutdownSnapshot(player);
            }
            return;
        }
        java.util.Set<UUID> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (Player player : players) {
            UUID uuid = player.getUniqueId();
            pending.add(uuid);
            try {
                PluginSchedulers.runOnEntity(this, player, () -> {
                    flushOneShutdownSnapshot(player);
                    pending.remove(uuid);
                });
            } catch (Exception e) {
                pending.remove(uuid);
            }
        }
        long deadline = System.currentTimeMillis() + 1500L;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!pending.isEmpty()) {
            getLogger().warning("Shutdown snapshot capture timed out for " + pending.size()
                    + " player(s); continuing shutdown");
        }
    }

    private void flushOneShutdownSnapshot(Player player) {
        UUID uuid = player.getUniqueId();
        try {
            SnapshotMessage message = captureService.capture(player, "shutdown");
            if (message != null) {
                ipcClient.putSnapshot(uuid, message, 500L).whenComplete((seq, error) -> {
                    if (error != null) {
                        getLogger().warning("Failed to persist shutdown snapshot for " + uuid + ": "
                                + error.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            getLogger().warning("Failed to flush shutdown snapshot for " + uuid + ": " + e.getMessage());
        }
    }

    public PaperConfig config() {
        return config;
    }

    public CaptureService captureService() {
        return captureService;
    }

    public ApplyService applyService() {
        return applyService;
    }

    public PendingSnapshots pendingSnapshots() {
        return pendingSnapshots;
    }
}
