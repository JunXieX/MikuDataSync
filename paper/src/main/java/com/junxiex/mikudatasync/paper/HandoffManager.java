package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Opcode;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the proxy-requested flush before a server switch: close containers, capture the final
 * snapshot on the player's entity/region thread, freeze the player until disconnect, and answer
 * with PUT. Works on Paper and Folia through PluginSchedulers.
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
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null) {
            ipcClient.respond(requestId, Opcode.ERROR, error("player not online"));
            return;
        }
        PluginSchedulers.runOnEntityOrElse(plugin, player,
                () -> captureAndRespond(player, requestId),
                () -> ipcClient.respond(requestId, Opcode.ERROR, error("player retired")));
    }

    private void captureAndRespond(Player player, long requestId) {
        UUID uuid = player.getUniqueId();
        handoff.add(uuid);
        // Take the cursor item out of the menu first so closing the inventory cannot drop or
        // move it; it is transferred as part of the snapshot instead.
        ItemStack carried = NmsBridge.getCarriedItem(player);
        NmsBridge.setCarriedItem(player, null);
        try {
            player.closeInventory();
        } catch (Exception ignored) {
        }
        SnapshotMessage message = captureService.capture(player, "switch", carried);
        // A successful gate does not guarantee the target connection succeeds; never leave the
        // player frozen on the source server forever.
        PluginSchedulers.runDelayed(plugin, player, () -> handoff.remove(uuid), 200L);
        if (message == null) {
            ipcClient.respond(requestId, Opcode.ERROR, error("capture failed"));
            return;
        }
        ipcClient.respond(requestId, Opcode.PUT, IpcPayloads.putRequest(uuid, message));
    }

    private static byte[] error(String message) {
        return new ByteWriter(64).writeUtf(message).toByteArray();
    }
}
