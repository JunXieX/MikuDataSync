package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.Frame;
import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Opcode;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    /** Freeze window after a flush; the proxy aborts explicitly, this only bounds the worst case. */
    private static final long UNFREEZE_TICKS = 60L;

    /** Budget for the PUT that carries the handoff snapshot. */
    private static final long PUT_TIMEOUT_MS = 5_000L;

    private final Set<UUID> handoff = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, Carried> carriedItems = new ConcurrentHashMap<>();
    /** Commit outcome of the handoff PUT: true = snapshot stored, false = not stored. */
    private final java.util.Map<UUID, CompletableFuture<Boolean>> commits = new ConcurrentHashMap<>();

    /**
     * A cursor item taken out of the menu, together with the place it was taken from. The location is
     * captured while the player is certainly online: once the entity is gone, a drop needs the world
     * and position that were recorded earlier.
     */
    private record Carried(ItemStack item, org.bukkit.World world, org.bukkit.Location location) {
    }

    public HandoffManager(MikuDataSyncPaper plugin, IpcClient ipcClient, CaptureService captureService) {
        this.plugin = plugin;
        this.ipcClient = ipcClient;
        this.captureService = captureService;
    }

    public boolean isHandingOff(UUID uuid) {
        return handoff.contains(uuid);
    }

    /**
     * Releases the freeze when the player is leaving anyway (quit during a handoff). The cursor item
     * follows the commit result: it is dropped into the world only when the snapshot did not store it.
     */
    public void clear(UUID uuid) {
        handoff.remove(uuid);
        Carried carried = carriedItems.remove(uuid);
        if (carried == null) {
            commits.remove(uuid);
            return;
        }
        CompletableFuture<Boolean> commit = commits.remove(uuid);
        if (commit == null) {
            // The PUT was never sent, so nothing carries the item.
            dropInWorld(uuid, carried);
            return;
        }
        // The player left before the commit result was known. Waiting for it is what separates
        // "the snapshot holds this item" (discard the local copy) from "nothing holds it" (drop it).
        commit.whenComplete((stored, error) -> {
            if (Boolean.TRUE.equals(stored)) {
                return;
            }
            dropInWorld(uuid, carried);
        });
    }

    /**
     * Handles HANDOFF_ABORT. When the proxy already committed the handoff snapshot the cursor item
     * lives inside that snapshot, so putting it back into the menu here would hand the player a
     * second copy on the next login. In that case the item is returned to the inventory instead
     * (the snapshot keeps its own copy and is replaced by the next capture anyway).
     */
    public void abort(UUID uuid, boolean snapshotCommitted) {
        Carried carried = carriedItems.remove(uuid);
        CompletableFuture<Boolean> commit = commits.remove(uuid);
        if (commit != null && !commit.isDone() && !snapshotCommitted) {
            // The PUT is still in flight: deciding now could hand the item back while the snapshot is
            // being stored, which duplicates it. The decision is taken when the outcome arrives.
            commit.whenComplete((stored, error) -> applyAbort(uuid, carried,
                    Boolean.TRUE.equals(stored) || snapshotCommitted));
            return;
        }
        // The proxy's opinion and the local commit result can disagree; the snapshot counts as stored
        // if either says so, because that is what decides whether a second copy may be handed out.
        applyAbort(uuid, carried, snapshotCommitted || (commit != null && commit.getNow(false)));
    }

    private void applyAbort(UUID uuid, Carried carried, boolean stored) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            handoff.remove(uuid);
            if (carried != null && !stored) {
                // The player is already gone and nothing else carries the item. A world drop at the
                // recorded position is the only form that survives entity removal.
                dropInWorld(uuid, carried);
            }
            return;
        }
        // The abort frame arrives on the IPC reader thread; inventory/menu state belongs to the
        // entity thread. Unfreezing happens inside that task so the player cannot pick up an item
        // and have it silently overwritten by the restore below.
        PluginSchedulers.runOnEntity(plugin, player, () -> {
            try {
                if (carried == null) {
                    return;
                }
                ItemStack current = NmsBridge.getCarriedItem(player);
                if (current != null && !current.getType().isAir()) {
                    // The player put something on the cursor in the meantime; never overwrite it.
                    returnItemToInventory(player, carried.item());
                    return;
                }
                if (stored) {
                    // The snapshot holds the item, but the player is still on this server and the
                    // snapshot will be replaced by the next capture, so the item goes back into the
                    // inventory rather than onto the cursor (which the next menu open would discard).
                    returnItemToInventory(player, carried.item());
                    plugin.getLogger().fine("Cursor item for " + uuid
                            + " was returned to the inventory after an aborted switch");
                } else {
                    NmsBridge.setCarriedItem(player, carried.item());
                }
            } finally {
                handoff.remove(uuid);
            }
        }, () -> {
            handoff.remove(uuid);
            Carried retiredCarried = carriedItems.remove(uuid);
            if (retiredCarried != null) {
                dropInWorld(uuid, retiredCarried);
            }
        });
    }

    /** Drops an item that can no longer be handed to the player, at the position it was taken from. */
    private void dropInWorld(UUID uuid, Carried carried) {
        try {
            if (carried.world() != null && carried.location() != null) {
                carried.world().dropItemNaturally(carried.location(), carried.item());
                plugin.getLogger().warning("Cursor item for " + uuid
                        + " was dropped into the world because the player was no longer available");
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to drop cursor item for " + uuid + ": " + e.getMessage());
        }
        plugin.getLogger().severe("Cursor item for " + uuid
                + " could not be returned to the player or the world: " + carried.item().getType());
    }

    private static void returnItemToInventory(Player player, ItemStack item) {
        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack remaining : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), remaining);
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

    /**
     * A failed commit means the snapshot never made it into the journal, so nothing else carries the
     * cursor item; it goes back into the inventory while the player is still online.
     */
    private void releaseAfterFailedCommit(UUID uuid) {
        Carried carried = carriedItems.remove(uuid);
        handoff.remove(uuid);
        commits.remove(uuid);
        if (carried == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            dropInWorld(uuid, carried);
            return;
        }
        PluginSchedulers.runOnEntity(plugin, player, () -> returnItemToInventory(player, carried.item()));
    }

    private void captureAndRespond(Player player, long requestId) {
        UUID uuid = player.getUniqueId();
        // The commit future is published together with the freeze: from this instant on, a quit that
        // runs clear() must see "commit pending" instead of "no commit", otherwise it would drop a
        // copy of the cursor item that the snapshot is about to store.
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        commits.put(uuid, outcome);
        handoff.add(uuid);
        // Take the cursor item out of the menu first so closing the inventory cannot drop or
        // move it; it is transferred as part of the snapshot instead. If cursor sync is disabled
        // the item must stay in the menu, otherwise it would be lost.
        boolean cursorSync = captureService.capturesCursor();
        ItemStack carried = cursorSync ? NmsBridge.getCarriedItem(player) : null;
        if (cursorSync) {
            if (carried != null) {
                // Remember where the item came from: if the player is gone by the time it has to be
                // returned, this is the only way to place it back into the world.
                carriedItems.put(uuid, new Carried(carried, player.getWorld(), player.getLocation()));
            }
            NmsBridge.setCarriedItem(player, null);
        }
        if (cursorSync) {
            try {
                player.closeInventory();
            } catch (Exception ignored) {
            }
        }
        SnapshotMessage message = captureService.capture(player, "switch", carried);
        if (message == null) {
            // Capture failed, so the switch will be refused: release the freeze immediately instead
            // of leaving the player unable to interact for the whole unfreeze window.
            handoff.remove(uuid);
            commits.remove(uuid);
            if (cursorSync) {
                carriedItems.remove(uuid);
                NmsBridge.setCarriedItem(player, carried);
            }
            ipcClient.respond(requestId, Opcode.ERROR, error("capture failed"));
            return;
        }
        // The reply answers the proxy's FLUSH_REQ id; the commit result arrives on the same future.
        // Only a successful commit proves the snapshot holds the cursor item, so the local copy is
        // released solely in that case.
        CompletableFuture<Frame> commit = ipcClient.request(Opcode.PUT,
                IpcPayloads.putRequest(uuid, message), PUT_TIMEOUT_MS, requestId);
        // The cursor item is released only when the commit result is known: a success means the
        // snapshot carries it (the local copy is discarded), a failure means nothing carries it (it is
        // handed back). Deciding earlier would duplicate the item or lose it.
        commit.whenComplete((frame, error) -> {
            boolean stored = error == null;
            outcome.complete(stored);
            commits.remove(uuid, outcome);
            if (stored) {
                Carried committedCopy = carriedItems.remove(uuid);
                if (committedCopy != null) {
                    plugin.getLogger().fine("Cursor item for " + uuid
                            + " is carried by the committed switch snapshot");
                }
            } else {
                plugin.getLogger().warning("Handoff snapshot for " + uuid + " was not committed: "
                        + error.getMessage());
                releaseAfterFailedCommit(uuid);
            }
        });
        // The freeze itself still needs a bounded worst case: a passed gate does not guarantee the
        // target connection succeeds, and a player must not stay frozen forever.
        PluginSchedulers.runDelayed(plugin, player, () -> handoff.remove(uuid),
                () -> handoff.remove(uuid), UNFREEZE_TICKS);
    }

    private static byte[] error(String message) {
        return new ByteWriter(64).writeUtf(message).toByteArray();
    }
}