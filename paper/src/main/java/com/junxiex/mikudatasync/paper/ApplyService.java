package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.AttributeList;
import com.junxiex.mikudatasync.common.CoreData;
import com.junxiex.mikudatasync.common.EffectList;
import com.junxiex.mikudatasync.common.MiscData;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import org.bukkit.GameMode;
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

    private final PaperConfig config;

    public ApplyService(PaperConfig config) {
        this.config = config;
    }

    public void apply(Player player, SnapshotEnvelope envelope) throws Exception {
        if (envelope.schemaVersion() != Protocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported schema version: " + envelope.schemaVersion());
        }
        if (envelope.dataVersion() != NmsBridge.dataVersion()) {
            throw new ProtocolException("data version mismatch: snapshot=" + envelope.dataVersion()
                    + ", local=" + NmsBridge.dataVersion());
        }

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
        if (pdc != null) {
            player.getPersistentDataContainer().readFromBytes(pdc, true);
        }
        if (misc != null && misc.hasCursorItem()) {
            NmsBridge.setCarriedItem(player, ItemStack.deserializeBytes(misc.cursorItem()));
        }
        if (advancements != null) {
            NmsBridge.applyAdvancements(player, advancements, config.advancementStrict());
        }
        if (statistics != null) {
            NmsBridge.applyStatistics(player, statistics);
        }
        player.updateInventory();
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
