package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.common.Opcode;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Applies a snapshot restored by the proxy directly to the online player, then asks them to rejoin. */
public final class RestoreHandler {

    private final MikuDataSyncPaper plugin;
    private final IpcClient ipcClient;
    private final ApplyService applyService;

    public RestoreHandler(MikuDataSyncPaper plugin, IpcClient ipcClient, ApplyService applyService) {
        this.plugin = plugin;
        this.ipcClient = ipcClient;
        this.applyService = applyService;
    }

    public void handle(byte[] payload, long requestId) {
        try {
            UUID[] uuid = new UUID[1];
            SnapshotMessage message = IpcPayloads.readPutRequest(payload, uuid);
            Player player = plugin.getServer().getPlayer(uuid[0]);
            if (player == null) {
                return;
            }
            SnapshotEnvelope envelope = SnapshotEnvelope.decode(message.envelope());
            ApplyService.Prepared prepared = applyService.prepare(envelope);
            PluginSchedulers.runOnEntityOrElse(plugin, player, () -> {
                try {
                    applyService.apply(player, prepared);
                    ipcClient.respond(requestId, Opcode.RESTORE_RES, new byte[0]);
                    player.sendMessage(Component.text("数据已恢复"));
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to apply restored snapshot: " + e.getMessage());
                    ipcClient.respond(requestId, Opcode.ERROR,
                            new com.junxiex.mikudatasync.common.ByteWriter(128).writeUtf(e.getMessage()).toByteArray());
                    player.sendMessage(Component.text("数据恢复失败，请联系管理员"));
                }
            }, () -> {
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid restore payload: " + e.getMessage());
        }
    }
}
