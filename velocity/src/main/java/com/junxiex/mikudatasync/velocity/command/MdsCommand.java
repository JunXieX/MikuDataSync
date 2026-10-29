package com.junxiex.mikudatasync.velocity.command;

import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Metrics;
import com.junxiex.mikudatasync.common.Opcode;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
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

    private static final String DEFAULT_PERMISSION = "mikudatasync.admin";

    /**
     * Admin file work (backup write, restore read, backup listing) gets its own thread: sharing the
     * journal commit executor would let one large backup delay every player's switch.
     */
    private final java.util.concurrent.ExecutorService adminIo = java.util.concurrent.Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "mds-admin-io");
                thread.setDaemon(true);
                return thread;
            });

    private void runAdmin(Runnable task) {
        try {
            adminIo.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            plugin.logger().warn("Admin file task rejected; the proxy is shutting down");
        }
    }

    /** Stops the admin file thread when the proxy shuts down. */
    public void close() {
        adminIo.shutdown();
    }

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
        // An empty node means "no restriction was configured"; it no longer defaults to open, so a
        // stock install cannot be used by players to back up and restore their own state.
        String permission = config.commandPermission();
        if (permission == null || permission.isBlank()) {
            permission = DEFAULT_PERMISSION;
        }
        return invocation.source().hasPermission(permission);
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
                    (plugin.startFailure() == null ? "ok: config loaded"
                            : "FAILED to start: " + plugin.startFailure())
                            + " | ipc=" + (ipcServer == null ? "down" : ipcServer.isRunning() ? "up" : "down")
                            + " | failMode=" + config.failMode()
                            + " | journal=" + (store == null ? "disabled" : store.journalStatus())));
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
        var target = player.get();
        var current = target.getCurrentServer();
        if (current.isEmpty()) {
            source.sendMessage(Component.text("Player is not connected to a backend"));
            return;
        }
        ipcServer.request(current.get().getServerInfo().getName(), Opcode.BACKUP_REQ,
                        IpcPayloads.uuid(target.getUniqueId()), 5000L)
                .whenComplete((frame, error) -> {
                    if (error != null) {
                        source.sendMessage(Component.text("Backup failed: " + error.getMessage()));
                        return;
                    }
                    // The callback runs on the backend reader thread; backup writes fsync, so it
                    // goes to the disk executor instead of stalling that backend's frame loop.
                    runAdmin(() -> {
                        try {
                            SnapshotMessage message = SnapshotMessage.decode(frame.payload());
                            var file = backups.write(target.getUniqueId(), target.getUsername(), message);
                            source.sendMessage(Component.text("Backup written (live capture): " + file.getFileName()));
                        } catch (Exception e) {
                            source.sendMessage(Component.text("Backup failed: " + e.getMessage()));
                        }
                    });
                });
    }

    private void listBackups(CommandSource source) {
        runAdmin(() -> listBackupsOnDisk(source));
    }

    private void listBackupsOnDisk(CommandSource source) {
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
            source.sendMessage(Component.text("Usage: /mds restore <player> <backup-file>"));
            return;
        }
        var player = server.getPlayer(arguments[1]);
        if (player.isEmpty()) {
            source.sendMessage(Component.text("Player not online: " + arguments[1]));
            return;
        }
        var target = player.get();
        // A backup must belong to the target player. The old check only ran when the file name
        // happened to contain a UUID, so any other name skipped it entirely.
        if (!BackupService.belongsTo(arguments[2], target.getUniqueId())) {
            source.sendMessage(Component.text("Backup belongs to another player"));
            return;
        }
        runAdmin(() -> {
            try {
                SnapshotMessage message = backups.read(arguments[2]);
                SnapshotEnvelope parsed = SnapshotEnvelope.decode(message.envelope());
                if (parsed.schemaVersion() > Protocol.SCHEMA_VERSION) {
                    throw new IllegalStateException("backup schema is newer than this build");
                }
                var current = target.getCurrentServer();
                if (current.isEmpty()) {
                    source.sendMessage(Component.text("Restore needs the player to be on a backend server"));
                    return;
                }
                // The snapshot must not become authoritative before the backend accepted it: committing
                // first left an unappliable snapshot at the highest seq, and the pre-login fetch then
                // refused every later login for that player.
                ipcServer.request(current.get().getServerInfo().getName(), Opcode.RESTORE_APPLY,
                                IpcPayloads.putRequest(target.getUniqueId(), message), 5000L)
                        .whenComplete((frame, error) -> {
                            if (error != null) {
                                source.sendMessage(Component.text("Restore failed: " + error.getMessage()));
                                return;
                            }
                            runAdmin(() -> commitRestored(source, target, message, arguments[1]));
                        });
            } catch (Exception e) {
                source.sendMessage(Component.text("Restore failed: " + e.getMessage()));
            }
        });
    }

    /** Records the applied backup so a later switch or rejoin starts from the restored state. */
    private void commitRestored(CommandSource source, Player target, SnapshotMessage message, String name) {
        try {
            long seq = store.commit(target.getUniqueId(), message);
            source.sendMessage(Component.text("Restored and applied " + name + " at seq " + seq));
        } catch (Exception e) {
            source.sendMessage(Component.text("Backup was applied on the backend but the snapshot commit "
                    + "failed: " + e.getMessage()));
        }
    }
}