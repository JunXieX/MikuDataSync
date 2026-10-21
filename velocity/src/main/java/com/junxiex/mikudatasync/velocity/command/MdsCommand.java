package com.junxiex.mikudatasync.velocity.command;

import com.junxiex.mikudatasync.common.Metrics;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.velocity.IpcServer;
import com.junxiex.mikudatasync.velocity.MikuDataSyncVelocity;
import com.junxiex.mikudatasync.velocity.SnapshotStore;
import com.junxiex.mikudatasync.velocity.VelocityConfig;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class MdsCommand implements SimpleCommand {

    private final MikuDataSyncVelocity plugin;
    private final ProxyServer server;
    private final SnapshotStore store;
    private final IpcServer ipcServer;
    private final VelocityConfig config;

    public MdsCommand(MikuDataSyncVelocity plugin, ProxyServer server, SnapshotStore store,
                      IpcServer ipcServer, VelocityConfig config) {
        this.plugin = plugin;
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
                            + " | ipc=" + (ipcServer.isRunning() ? "running" : "stopped")
                            + " | " + Metrics.gateSummary()));
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
        attempt(player, target.get(), targetName, 0);
    }

    private void attempt(Player player, RegisteredServer target, String targetName, int tries) {
        player.createConnectionRequest(target).connect().whenComplete((result, error) -> {
            boolean failed = error != null || !result.isSuccessful();
            if (failed && tries < 1) {
                plugin.logger().info("Retrying switch for {} to {} after first failure: {}",
                        player.getUsername(), targetName, error == null ? result.getStatus() : error.getMessage());
                server.getScheduler().buildTask(plugin,
                        () -> attempt(player, target, targetName, tries + 1)).delay(1, TimeUnit.SECONDS).schedule();
                return;
            }
            if (failed) {
                player.sendMessage(Component.text("Switch failed: " + (error != null ? error.getMessage() : result.getStatus())));
            } else {
                player.sendMessage(Component.text("Switching to " + targetName));
            }
        });
    }
}
