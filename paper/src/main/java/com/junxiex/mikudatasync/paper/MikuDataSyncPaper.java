package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Opcode;
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

        NmsBridge.initialize(getLogger());
        ipcClient = new IpcClient(this, config);
        captureService = new CaptureService(getLogger(), config);
        applyService = new ApplyService(config);
        pendingSnapshots = new PendingSnapshots();
        handoffManager = new HandoffManager(this, ipcClient, captureService);

        ipcClient.setFrameHandler(frame -> {
            try {
                switch (frame.opcode()) {
                    case FLUSH_REQ -> handoffManager.handleFlush(IpcPayloads.parseUuid(frame.payload()), frame.requestId());
                    case HANDOFF_ABORT -> handoffManager.clear(IpcPayloads.parseUuid(frame.payload()));
                    default -> {
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Failed to handle IPC frame " + frame.opcode() + ": " + e.getMessage());
            }
        });
        ipcClient.start();

        getServer().getPluginManager().registerEvents(
                new PlayerListener(this, config, ipcClient, captureService, applyService, pendingSnapshots, handoffManager), this);
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
        startPeriodicSnapshots();
        getLogger().info("MikuDataSync backend enabled for server " + config.serverId()
                + " (" + (PluginSchedulers.isFolia() ? "Folia" : "Paper") + ")");
    }

    private void startPeriodicSnapshots() {
        int seconds = config.periodicSnapshotSeconds();
        if (seconds <= 0) {
            return;
        }
        long ticks = 20L * seconds;
        PluginSchedulers.runTimer(this, this::snapshotOnlinePlayers, ticks, ticks);
    }

    private void snapshotOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
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
        if (ipcClient == null) {
            return;
        }
        if (captureService != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                try {
                    SnapshotMessage message = captureService.capture(player, "shutdown");
                    if (message != null) {
                        ipcClient.putSnapshot(player.getUniqueId(), message, 500L).get(1, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to flush shutdown snapshot: " + e.getMessage());
                }
            }
        }
        ipcClient.close();
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
