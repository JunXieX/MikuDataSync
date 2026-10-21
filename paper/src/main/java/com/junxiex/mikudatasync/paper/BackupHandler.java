package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Opcode;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Handles a proxy-requested live snapshot for /mds backup (no player freeze). */
public final class BackupHandler {

    private final MikuDataSyncPaper plugin;
    private final IpcClient ipcClient;
    private final CaptureService captureService;

    public BackupHandler(MikuDataSyncPaper plugin, IpcClient ipcClient, CaptureService captureService) {
        this.plugin = plugin;
        this.ipcClient = ipcClient;
        this.captureService = captureService;
    }

    public void handle(byte[] payload, long requestId) {
        try {
            UUID uuid = IpcPayloads.parseUuid(payload);
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) {
                ipcClient.respond(requestId, Opcode.ERROR, error("player not online"));
                return;
            }
            PluginSchedulers.runOnEntityOrElse(plugin, player, () -> {
                SnapshotMessage message = captureService.capture(player, "backup");
                if (message == null) {
                    ipcClient.respond(requestId, Opcode.ERROR, error("capture failed"));
                } else {
                    ipcClient.respond(requestId, Opcode.BACKUP_RES, message.encode());
                }
            }, () -> ipcClient.respond(requestId, Opcode.ERROR, error("player retired")));
        } catch (Exception e) {
            ipcClient.respond(requestId, Opcode.ERROR, error(String.valueOf(e.getMessage())));
        }
    }

    private static byte[] error(String message) {
        return new ByteWriter(64).writeUtf(message).toByteArray();
    }
}
