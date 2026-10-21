package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.AttributeList;
import com.junxiex.mikudatasync.common.CoreData;
import com.junxiex.mikudatasync.common.EffectList;
import com.junxiex.mikudatasync.common.Metrics;
import com.junxiex.mikudatasync.common.MapDataList;
import com.junxiex.mikudatasync.common.MiscData;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
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
 * Applies a decoded snapshot on the server thread. All segments are decoded before the first
 * mutation so a malformed snapshot cannot leave the player half updated.
 */
public final class ApplyService {

    private final org.bukkit.plugin.Plugin plugin;
    private final PaperConfig config;

    public ApplyService(org.bukkit.plugin.Plugin plugin, PaperConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void apply(Player player, SnapshotEnvelope envelope) throws Exception {
        if (envelope.schemaVersion() > Protocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported schema version: " + envelope.schemaVersion());
        }
        if (envelope.dataVersion() != NmsBridge.dataVersion()) {
            throw new ProtocolException("data version mismatch: snapshot=" + envelope.dataVersion()
                    + ", local=" + NmsBridge.dataVersion());
        }

        long start = System.nanoTime();
        // Decode everything first: any failure aborts before mutating the player.
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
        byte[] pdc = envelope.segment(Segment.PDC);
        byte[] advancements = envelope.segment(Segment.ADVANCEMENTS);
        byte[] statistics = envelope.segment(Segment.STATISTICS);

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
        byte[] maps = envelope.segment(Segment.MAPS);
        if (maps != null) {
            try {
                java.util.List<MapDataList.Entry> entries = MapDataList.decode(maps);
                PluginSchedulers.runDelayed(plugin, player, () -> PluginSchedulers.runOnGlobal(plugin, () -> {
                    try {
                        java.util.Map<Integer, Integer> mapped = NmsBridge.createMaps(player.getWorld(), entries);
                        PluginSchedulers.runOnEntity(plugin, player, () -> {
                            NmsBridge.rewriteMapItems(player, mapped);
                            player.updateInventory();
                        });
                    } catch (Exception e) {
                        Bukkit.getLogger().warning("Failed to create map data: " + e.getMessage());
                    }
                }), 40L);
            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to apply map data: " + e.getMessage());
            }
        }
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
        if (advancements != null) {
            NmsBridge.applyAdvancements(player, advancements, config.advancementStrict());
        }
        if (statistics != null) {
            NmsBridge.applyStatistics(player, statistics);
        }
        player.updateInventory();
        Metrics.recordApply(System.nanoTime() - start);
    }

    /**
     * Cursor slot is restored one tick after join: Paper's join sequence resets the menu carried
     * slot after PlayerJoinEvent, so setting it during the event is lost.
     */
    public void applyCursor(Player player, SnapshotEnvelope envelope) {
        if (!envelope.hasSegment(Segment.MISC)) {
            return;
        }
        try {
            MiscData misc = MiscData.decode(envelope.segment(Segment.MISC));
            if (!misc.hasCursorItem()) {
                return;
            }
            // The vanilla cursor slot lives on the current menu and is discarded/returned when a
            // new container opens; putting the item into the inventory is the only loss-free
            // representation across arbitrary menu transitions.
            ItemStack cursor = ItemStack.deserializeBytes(misc.cursorItem());
            var leftover = player.getInventory().addItem(cursor);
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to restore cursor item", e);
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
        ItemStack[] out = new ItemStack[expectedSlots];
        System.arraycopy(decoded, 0, out, 0, Math.min(decoded.length, expectedSlots));
        return out;
    }
}
