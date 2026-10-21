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
    private final java.util.Map<UUID, ItemStack> carriedItems = new ConcurrentHashMap<>();

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
        carriedItems.remove(uuid);
    }

    /** Restores the cursor item if the switch was aborted and the player is still on this server. */
    public void abort(UUID uuid) {
        handoff.remove(uuid);
        ItemStack carried = carriedItems.remove(uuid);
        if (carried == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            NmsBridge.setCarriedItem(player, carried);
        }
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
        // move it; it is transferred as part of the snapshot instead. If cursor sync is disabled
        // the item must stay in the menu, otherwise it would be lost.
        boolean cursorSync = captureService.capturesCursor();
        ItemStack carried = cursorSync ? NmsBridge.getCarriedItem(player) : null;
        if (cursorSync) {
            if (carried != null) {
                carriedItems.put(uuid, carried);
            }
            NmsBridge.setCarriedItem(player, null);
        }
        try {
            player.closeInventory();
        } catch (Exception ignored) {
        }
        SnapshotMessage message = captureService.capture(player, "switch", carried);
        // A successful gate does not guarantee the target connection succeeds; never leave the
        // player frozen on the source server forever.
        // Never auto-restore the cursor here: the snapshot already contains it and restoring on a
        // slow-but-successful switch would duplicate the item. Explicit HANDOFF_ABORT restores it.
        PluginSchedulers.runDelayed(plugin, player, () -> {
            handoff.remove(uuid);
            carriedItems.remove(uuid);
        }, 200L);
        if (message == null) {
            if (cursorSync) {
                carriedItems.remove(uuid);
                NmsBridge.setCarriedItem(player, carried);
            }
            ipcClient.respond(requestId, Opcode.ERROR, error("capture failed"));
            return;
        }
        ipcClient.respond(requestId, Opcode.PUT, IpcPayloads.putRequest(uuid, message));
    }

    private static byte[] error(String message) {
        return new ByteWriter(64).writeUtf(message).toByteArray();
    }
}
