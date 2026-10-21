package com.junxiex.mikudatasync.velocity.command;

import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.velocity.IpcServer;
import com.junxiex.mikudatasync.velocity.SnapshotStore;
import com.junxiex.mikudatasync.velocity.VelocityConfig;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;

import java.util.Locale;

public final class MdsCommand implements SimpleCommand {

    private final SnapshotStore store;
    private final IpcServer ipcServer;
    private final VelocityConfig config;

    public MdsCommand(SnapshotStore store, IpcServer ipcServer, VelocityConfig config) {
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
                            + " | maxSnapshotBytes=" + config.maxSnapshotBytes()
                            + " | fsync=" + config.fsyncMode()));
            case "doctor" -> source.sendMessage(Component.text(
                    "ok: config loaded | ipc=" + (ipcServer.isRunning() ? "up" : "down")
                            + " | failMode=" + config.failMode()));
            case "reload" -> source.sendMessage(Component.text(
                    "reload currently requires a proxy restart (configuration reload lands in M5)"));
            default -> source.sendMessage(Component.text("Usage: /mds <status|stats|doctor|reload>"));
        }
    }
}
