package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Applies a snapshot restored by the proxy directly to the online player, then asks them to rejoin. */
public final class RestoreHandler {

    private final MikuDataSyncPaper plugin;
    private final ApplyService applyService;

    public RestoreHandler(MikuDataSyncPaper plugin, ApplyService applyService) {
        this.plugin = plugin;
        this.applyService = applyService;
    }

    public void handle(byte[] payload) {
        try {
            UUID[] uuid = new UUID[1];
            SnapshotMessage message = IpcPayloads.readPutRequest(payload, uuid);
            Player player = plugin.getServer().getPlayer(uuid[0]);
            if (player == null) {
                return;
            }
            PluginSchedulers.runOnEntityOrElse(plugin, player, () -> {
                try {
                    SnapshotEnvelope envelope = SnapshotEnvelope.decode(message.envelope());
                    applyService.apply(player, envelope);
                    player.kick(Component.text("数据已恢复，请重新连接"));
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to apply restored snapshot: " + e.getMessage());
                    player.kick(Component.text("数据恢复失败，请联系管理员"));
                }
            }, () -> {
            });
        } catch (ProtocolException e) {
            plugin.getLogger().warning("Invalid restore payload: " + e.getMessage());
        }
    }
}
