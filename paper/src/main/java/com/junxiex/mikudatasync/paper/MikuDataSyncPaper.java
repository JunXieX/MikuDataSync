package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.paper.command.MdsCommand;
import com.junxiex.mikudatasync.paper.listener.PlayerListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class MikuDataSyncPaper extends JavaPlugin {

    private PaperConfig config;
    private IpcClient ipcClient;
    private CaptureService captureService;
    private ApplyService applyService;
    private PendingSnapshots pendingSnapshots;

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
        ipcClient.start();
        captureService = new CaptureService(getLogger(), config);
        applyService = new ApplyService(config);
        pendingSnapshots = new PendingSnapshots();

        getServer().getPluginManager().registerEvents(
                new PlayerListener(this, config, ipcClient, captureService, applyService, pendingSnapshots), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        MdsCommand.node(ipcClient, config),
                        "MikuDataSync administration",
                        java.util.List.of()
                ));
        startPeriodicSnapshots();
        getLogger().info("MikuDataSync backend enabled for server " + config.serverId());
    }

    private void startPeriodicSnapshots() {
        int seconds = config.periodicSnapshotSeconds();
        if (seconds <= 0) {
            return;
        }
        long ticks = 20L * seconds;
        getServer().getScheduler().runTaskTimer(this, this::snapshotOnlinePlayers, ticks, ticks);
    }

    private void snapshotOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            SnapshotMessage message = captureService.capture(player, "periodic");
            if (message == null) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            getServer().getScheduler().runTaskAsynchronously(this, () ->
                    ipcClient.putSnapshot(uuid, message, 5000L).whenComplete((seq, error) -> {
                        if (error != null) {
                            getLogger().fine("Periodic snapshot failed for " + uuid + ": " + error.getMessage());
                        }
                    }));
        }
    }

    @Override
    public void onDisable() {
        if (ipcClient != null) {
            ipcClient.close();
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
