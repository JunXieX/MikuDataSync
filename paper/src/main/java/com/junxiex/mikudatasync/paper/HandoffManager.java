package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Opcode;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the proxy-requested flush before a server switch: close containers, capture the final
 * snapshot on the main thread, freeze the player until disconnect, and answer with PUT.
 */
public final class HandoffManager {

    private final MikuDataSyncPaper plugin;
    private final IpcClient ipcClient;
    private final CaptureService captureService;
    private final Set<UUID> handoff = ConcurrentHashMap.newKeySet();

    public HandoffManager(MikuDataSyncPaper plugin, IpcClient ipcClient, CaptureService captureService) {
        this.plugin = plugin;
        this.ipcClient = ipcClient;
        this.captureService = captureService;
    }

    public boolean isHandingOff(UUID uuid) {
        return handoff.contains(uuid);
    }

    public void clear(UUID uuid) {
        handoff.remove(uuid);
    }

    public void handleFlush(UUID uuid, long requestId) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                ipcClient.respond(requestId, Opcode.ERROR, error("player not online"));
                return;
            }
            handoff.add(uuid);
            try {
                player.closeInventory();
            } catch (Exception ignored) {
            }
            SnapshotMessage message = captureService.capture(player, "switch");
            if (message == null) {
                ipcClient.respond(requestId, Opcode.ERROR, error("capture failed"));
                return;
            }
            ipcClient.respond(requestId, Opcode.PUT, IpcPayloads.putRequest(uuid, message));
        });
    }

    private static byte[] error(String message) {
        return new ByteWriter(64).writeUtf(message).toByteArray();
    }
}
