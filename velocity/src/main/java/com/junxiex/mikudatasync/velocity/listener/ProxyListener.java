package com.junxiex.mikudatasync.velocity.listener;

import com.junxiex.mikudatasync.velocity.IpcServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import org.slf4j.Logger;

/** Switch orchestration is completed in M3; M1 only observes and logs. */
public final class ProxyListener {

    private final Logger logger;
    private final IpcServer ipcServer;

    public ProxyListener(Logger logger, IpcServer ipcServer) {
        this.logger = logger;
        this.ipcServer = ipcServer;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        logger.debug("switch requested for {} (ipcRunning={})",
                event.getPlayer().getUsername(), ipcServer.isRunning());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        logger.debug("player disconnected: {}", event.getPlayer().getUsername());
    }
}
