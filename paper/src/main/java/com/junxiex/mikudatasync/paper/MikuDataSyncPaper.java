package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.paper.command.MdsCommand;
import com.junxiex.mikudatasync.paper.listener.PlayerListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class MikuDataSyncPaper extends JavaPlugin {

    private PaperConfig config;
    private IpcClient ipcClient;

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
        getServer().getPluginManager().registerEvents(new PlayerListener(this, ipcClient), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        MdsCommand.node(ipcClient, config),
                        "MikuDataSync administration",
                        java.util.List.of()
                ));
        getLogger().info("MikuDataSync backend enabled for server " + config.serverId());
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
}
