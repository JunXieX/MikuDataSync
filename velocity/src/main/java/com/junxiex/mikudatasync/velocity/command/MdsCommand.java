package com.junxiex.mikudatasync.velocity.command;

import com.junxiex.mikudatasync.common.Metrics;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.velocity.BackupService;
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
    private final BackupService backups;

    public MdsCommand(MikuDataSyncVelocity plugin, ProxyServer server, SnapshotStore store,
                      IpcServer ipcServer, VelocityConfig config, BackupService backups) {
        this.plugin = plugin;
        this.server = server;
        this.store = store;
        this.ipcServer = ipcServer;
        this.config = config;
        this.backups = backups;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return config.commandPermission().isEmpty()
                || invocation.source().hasPermission(config.commandPermission());
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
            case "backup" -> backup(source, arguments);
            case "backups" -> listBackups(source);
            case "restore", "import" -> restore(source, arguments);
            case "reload" -> source.sendMessage(Component.text(
                    "reload currently requires a proxy restart"));
            default -> source.sendMessage(Component.text("Usage: /mds <status|stats|doctor|switch|backup|backups|restore|reload>"));
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

    private void backup(CommandSource source, String[] arguments) {
        if (arguments.length < 2) {
            source.sendMessage(Component.text("Usage: /mds backup <player>"));
            return;
        }
        var player = server.getPlayer(arguments[1]);
        if (player.isEmpty()) {
            source.sendMessage(Component.text("Player not online: " + arguments[1]));
            return;
        }
        SnapshotStore.Entry entry = store.get(player.get().getUniqueId());
        if (entry == null) {
            source.sendMessage(Component.text("No snapshot for " + arguments[1]));
            return;
        }
        try {
            SnapshotMessage message = new SnapshotMessage(entry.seq(), entry.captureSeq(),
                    entry.saveCause(), entry.sourceServer(), entry.envelope());
            var file = backups.write(player.get().getUniqueId(), player.get().getUsername(), message);
            source.sendMessage(Component.text("Backup written: " + file.getFileName()));
        } catch (Exception e) {
            source.sendMessage(Component.text("Backup failed: " + e.getMessage()));
        }
    }

    private void listBackups(CommandSource source) {
        try {
            var files = backups.list();
            if (files.isEmpty()) {
                source.sendMessage(Component.text("No backups"));
                return;
            }
            int limit = Math.min(20, files.size());
            for (int i = 0; i < limit; i++) {
                var file = files.get(i);
                source.sendMessage(Component.text(file.getFileName() + " (" + file.toFile().length() + " bytes)"));
            }
        } catch (Exception e) {
            source.sendMessage(Component.text("Backup list failed: " + e.getMessage()));
        }
    }

    private void restore(CommandSource source, String[] arguments) {
        if (arguments.length < 3) {
            source.sendMessage(Component.text("Usage: /mds restore <player> <file>"));
            return;
        }
        var player = server.getPlayer(arguments[1]);
        if (player.isEmpty()) {
            source.sendMessage(Component.text("Player not online: " + arguments[1]));
            return;
        }
        try {
            SnapshotMessage message = backups.read(arguments[2]);
            long seq = store.commit(player.get().getUniqueId(), message);
            source.sendMessage(Component.text("Restored " + arguments[1] + " at seq " + seq
                    + " (applies on next join)"));
        } catch (Exception e) {
            source.sendMessage(Component.text("Restore failed: " + e.getMessage()));
        }
    }
}
