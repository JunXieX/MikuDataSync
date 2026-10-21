package com.junxiex.mikudatasync.velocity.command;

import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.velocity.IpcServer;
import com.junxiex.mikudatasync.velocity.SnapshotStore;
import com.junxiex.mikudatasync.velocity.VelocityConfig;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.Locale;

public final class MdsCommand implements SimpleCommand {

    private final ProxyServer server;
    private final SnapshotStore store;
    private final IpcServer ipcServer;
    private final VelocityConfig config;

    public MdsCommand(ProxyServer server, SnapshotStore store, IpcServer ipcServer, VelocityConfig config) {
        this.server = server;
        this.store = store;
        this.ipcServer = ipcServer;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] arguments = invocation.arguments();
        String sub = arguments.length == 0 ? "status" : arguments[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> source.sendMessage(Component.text(
                    "MikuDataSync | protocol=" + Protocol.PROTOCOL_VERSION
                            + " | snapshots=" + store.size()
                            + " | ipc=" + (ipcServer.isRunning() ? "running" : "stopped")));
            case "stats" -> source.sendMessage(Component.text(
                    "snapshots=" + store.size()
                            + " | journal=" + store.journalStatus()
                            + " | fsync=" + config.fsyncMode()));
            case "doctor" -> source.sendMessage(Component.text(
                    "ok: config loaded | ipc=" + (ipcServer.isRunning() ? "up" : "down")
                            + " | failMode=" + config.failMode()
                            + " | journal=" + store.journalStatus()));
            case "switch" -> switchServer(source, arguments);
            case "reload" -> source.sendMessage(Component.text(
                    "reload currently requires a proxy restart"));
            default -> source.sendMessage(Component.text("Usage: /mds <status|stats|doctor|switch|reload>"));
        }
    }

    private void switchServer(CommandSource source, String[] arguments) {
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can use /mds switch"));
            return;
        }
        if (arguments.length < 2) {
            source.sendMessage(Component.text("Usage: /mds switch <server>"));
            return;
        }
        String targetName = arguments[1];
        var target = server.getServer(targetName);
        if (target.isEmpty()) {
            source.sendMessage(Component.text("Unknown server: " + targetName));
            return;
        }
        player.createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
            if (error != null) {
                player.sendMessage(Component.text("Switch failed: " + error.getMessage()));
            } else if (!result.isSuccessful()) {
                player.sendMessage(Component.text("Switch failed: " + result.getStatus()));
            } else {
                player.sendMessage(Component.text("Switching to " + targetName));
            }
        });
    }
}
