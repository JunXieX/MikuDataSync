package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.AdvancementList;
import com.junxiex.mikudatasync.common.AttributeList;
import com.junxiex.mikudatasync.common.CoreData;
import com.junxiex.mikudatasync.common.EffectList;
import com.junxiex.mikudatasync.common.Metrics;
import com.junxiex.mikudatasync.common.MiscData;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.StageMetrics;
import com.junxiex.mikudatasync.common.StatisticsList;
import com.junxiex.mikudatasync.paper.api.MikuDataSyncApplyEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a decoded snapshot on the server thread. {@link #prepare(SnapshotEnvelope)} decodes every
 * segment up front so a malformed snapshot is rejected before the first mutation; apply() only
 * writes.
 */
public final class ApplyService {

    private final PaperConfig config;

    public ApplyService(PaperConfig config) {
        this.config = config;
    }

    public record Prepared(SnapshotEnvelope envelope, CoreData core, List<EffectList.Entry> effects,
                           List<AttributeList.Entry> attributes, ItemStack[] inventory, ItemStack[] enderChest,
                           ItemStack cursorItem, byte[] pdc,
                           List<AdvancementList.Entry> advancements,
                           List<StatisticsList.Entry> statistics, byte[] maps) {
    }

    /** Decodes the snapshot off the entity thread; apply() then only performs mutations. */
    public Prepared prepare(SnapshotEnvelope envelope) throws Exception {
        if (envelope.schemaVersion() > Protocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported schema version: " + envelope.schemaVersion());
        }
        if (envelope.dataVersion() != NmsBridge.dataVersion()) {
            throw new ProtocolException("data version mismatch: snapshot=" + envelope.dataVersion()
                    + ", local=" + NmsBridge.dataVersion());
        }
        long stageStart = System.nanoTime();
        CoreData core = envelope.hasSegment(Segment.CORE) ? CoreData.decode(envelope.segment(Segment.CORE)) : null;
        List<EffectList.Entry> effects = envelope.hasSegment(Segment.EFFECTS)
                ? EffectList.decode(envelope.segment(Segment.EFFECTS)) : null;
        List<AttributeList.Entry> attributes = envelope.hasSegment(Segment.ATTRIBUTES)
                ? AttributeList.decode(envelope.segment(Segment.ATTRIBUTES)) : null;
        ItemStack[] inventory = envelope.hasSegment(Segment.INVENTORY)
                ? decodeItems(envelope.segment(Segment.INVENTORY), 41) : null;
        ItemStack[] enderChest = envelope.hasSegment(Segment.ENDER_CHEST)
                ? decodeItems(envelope.segment(Segment.ENDER_CHEST), 27) : null;
        MiscData misc = envelope.hasSegment(Segment.MISC) ? MiscData.decode(envelope.segment(Segment.MISC)) : null;
        // Advancements and statistics must be decoded here as well: decoding them inside apply()
        // would throw after inventory/PDC/health were already overwritten, leaving a half-applied
        // player that is then kicked and repeats the same failure on every reconnect.
        List<AdvancementList.Entry> advancements = envelope.hasSegment(Segment.ADVANCEMENTS)
                ? AdvancementList.decode(envelope.segment(Segment.ADVANCEMENTS)) : null;
        List<StatisticsList.Entry> statistics = envelope.hasSegment(Segment.STATISTICS)
                ? StatisticsList.decode(envelope.segment(Segment.STATISTICS)) : null;
        // The cursor item is decoded here as well: deserialising it inside apply() threw after the
        // inventory and health were already overwritten, which is exactly the half-applied state this
        // method is supposed to rule out.
        ItemStack cursorItem = null;
        if (misc != null && misc.hasCursorItem()) {
            cursorItem = ItemStack.deserializeBytes(misc.cursorItem());
            if (cursorItem.getType().isAir()) {
                cursorItem = null;
            }
        }
        byte[] maps = envelope.segment(Segment.MAPS);
        StageMetrics.record("apply.decode", System.nanoTime() - stageStart);
        return new Prepared(envelope, core, effects, attributes, inventory, enderChest, cursorItem,
                envelope.segment(Segment.PDC), advancements, statistics, maps);
    }

    public void apply(Player player, SnapshotEnvelope envelope) throws Exception {
        apply(player, prepare(envelope));
    }

    public void apply(Player player, Prepared prepared) throws Exception {
        SnapshotEnvelope envelope = prepared.envelope();
        CoreData core = prepared.core();
        List<EffectList.Entry> effects = prepared.effects();
        List<AttributeList.Entry> attributes = prepared.attributes();
        ItemStack[] inventory = prepared.inventory();
        ItemStack[] enderChest = prepared.enderChest();
        byte[] pdc = prepared.pdc();
        List<AdvancementList.Entry> advancements = prepared.advancements();
        List<StatisticsList.Entry> statistics = prepared.statistics();
        byte[] maps = prepared.maps();

        long start = System.nanoTime();
        long stageStart = System.nanoTime();

        Bukkit.getPluginManager().callEvent(new MikuDataSyncApplyEvent(player, envelope));
        if (core != null) {
            player.setGameMode(GameMode.valueOf(core.gameMode()));
        }
        if (attributes != null) {
            for (AttributeList.Entry entry : attributes) {
                NamespacedKey key = NamespacedKey.fromString(entry.attributeKey());
                Attribute attribute = key == null ? null : Registry.ATTRIBUTE.get(key);
                AttributeInstance instance = attribute == null ? null : player.getAttribute(attribute);
                if (instance != null) {
                    instance.setBaseValue(entry.baseValue());
                }
            }
        }
        if (effects != null) {
            for (PotionEffect active : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(active.getType());
            }
            for (EffectList.Entry entry : effects) {
                NamespacedKey key = NamespacedKey.fromString(entry.typeKey());
                PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
                if (type != null) {
                    player.addPotionEffect(new PotionEffect(type, entry.duration(), entry.amplifier(),
                            entry.ambient(), entry.particles(), entry.icon()));
                }
            }
        }
        if (core != null) {
            player.setAllowFlight(core.allowFlight());
            player.setFlying(core.allowFlight() && core.flying());
            player.setFlySpeed(clamp(core.flySpeed()));
            player.setWalkSpeed(clamp(core.walkSpeed()));
            player.setFireTicks(core.fireTicks());
            player.setFreezeTicks(core.freezeTicks());
            player.setRemainingAir(core.remainingAir());
            player.setFallDistance(0.0f);

            player.setFoodLevel(core.food());
            player.setSaturation(core.saturation());
            player.setExhaustion(core.exhaustion());
            player.setExperienceLevelAndProgress(core.totalExp());

            AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
            double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
            if (!Double.isFinite(maxHealth) || maxHealth < 1.0) {
                maxHealth = 1.0;
            }
            player.setHealth(Math.max(1.0, Math.min(core.health(), maxHealth)));
            player.setAbsorptionAmount(core.absorption());
        }
        stageStart = System.nanoTime();
        if (inventory != null) {
            player.getInventory().setContents(inventory);
        }
        if (enderChest != null) {
            player.getEnderChest().setContents(enderChest);
        }
        if (core != null) {
            int slot = Math.max(0, Math.min(8, core.heldSlot()));
            player.getInventory().setHeldItemSlot(slot);
        }
        // Cursor item is folded into the inventory right after the inventory write: it is the only
        // loss-free representation across menu transitions, and doing it here means a later failure
        // in the optional segments cannot drop it.
        if (prepared.cursorItem() != null) {
            applyCursorItem(player, prepared.cursorItem());
        }
        StageMetrics.record("apply.inventory", System.nanoTime() - stageStart);
        if (maps != null) {
            try {
                NmsBridge.applyItemMaps(player, maps);
            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to apply map data: " + e.getMessage());
            }
        }
        stageStart = System.nanoTime();
        if (pdc != null) {
            if (config.pdcMerge()) {
                ItemStack carrier = new ItemStack(Material.STONE);
                var meta = carrier.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().readFromBytes(pdc, true);
                    meta.getPersistentDataContainer().copyTo(player.getPersistentDataContainer(), false);
                }
            } else {
                player.getPersistentDataContainer().readFromBytes(pdc, true);
            }
        }
        StageMetrics.record("apply.pdc", System.nanoTime() - stageStart);
        // Advancements and statistics are NMS-reflective and non-critical: a failure here must not
        // abort the apply after the inventory was already written, because the player would then be
        // kicked on every reconnect with no way out.
        stageStart = System.nanoTime();
        if (advancements != null) {
            try {
                NmsBridge.applyAdvancements(player, advancements, config.advancementStrict());
            } catch (Throwable t) {
                // Degraded, not failed: the apply completes and the player keeps playing. Counting it as
                // a failure would make "apply n" and "apply failed" both increase for one login.
                Bukkit.getLogger().warning("Failed to apply advancements for " + player.getName() + ": " + t);
            }
        }
        StageMetrics.record("apply.advancements", System.nanoTime() - stageStart);
        stageStart = System.nanoTime();
        if (statistics != null) {
            try {
                NmsBridge.applyStatistics(player, statistics);
            } catch (Throwable t) {
                Bukkit.getLogger().warning("Failed to apply statistics for " + player.getName() + ": " + t);
            }
        }
        StageMetrics.record("apply.statistics", System.nanoTime() - stageStart);
        stageStart = System.nanoTime();
        // The cursor item is folded into the inventory here rather than one tick later: the delayed
        // variant lost the item whenever the player disconnected inside that tick, because the quit
        // capture then overwrote the snapshot without it.
        player.updateInventory();
        StageMetrics.record("apply.flush", System.nanoTime() - stageStart);
        Metrics.recordApply(System.nanoTime() - start);
    }

    /**
     * The vanilla cursor slot lives on the current menu and is discarded when a new container opens,
     * so the cursor item is moved into the inventory: that is the only loss-free representation
     * across arbitrary menu transitions.
     */
    private void applyCursorItem(Player player, ItemStack cursor) {
        var leftover = player.getInventory().addItem(cursor);
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    }

    private static float clamp(float speed) {
        return Math.max(-1.0f, Math.min(1.0f, speed));
    }

    private static ItemStack[] decodeItems(byte[] payload, int expectedSlots) throws ProtocolException {
        ItemStack[] decoded = ItemStack.deserializeItemsFromBytes(payload);
        if (decoded == null) {
            throw new ProtocolException("empty item array");
        }
        if (decoded.length > expectedSlots) {
            // A longer array means the snapshot was written by a different slot layout; dropping the
            // extras silently would lose items without a trace.
            Bukkit.getLogger().warning("Snapshot carries " + decoded.length + " item slots but this "
                    + "server expects " + expectedSlots + "; extra slots are ignored");
        }
        ItemStack[] out = new ItemStack[expectedSlots];
        System.arraycopy(decoded, 0, out, 0, Math.min(decoded.length, expectedSlots));
        return out;
    }
}
