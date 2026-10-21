package com.junxiex.mikudatasync.paper.listener;

import com.junxiex.mikudatasync.paper.IpcClient;
import com.junxiex.mikudatasync.paper.MikuDataSyncPaper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * M1 wiring only. Capture/apply logic lands in M2, switch orchestration in M3.
 */
public final class PlayerListener implements Listener {

    private final MikuDataSyncPaper plugin;
    private final IpcClient ipcClient;

    public PlayerListener(MikuDataSyncPaper plugin, IpcClient ipcClient) {
        this.plugin = plugin;
        this.ipcClient = ipcClient;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        plugin.getLogger().fine(() -> "prelogin " + event.getUniqueId() + " ipc=" + ipcClient.state());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getLogger().fine(() -> "join " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getLogger().fine(() -> "quit " + event.getPlayer().getName());
    }
}
