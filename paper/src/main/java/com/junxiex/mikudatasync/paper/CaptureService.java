package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.AttributeList;
import com.junxiex.mikudatasync.common.CoreData;
import com.junxiex.mikudatasync.common.EffectList;
import com.junxiex.mikudatasync.common.MiscData;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Captures a player snapshot. Runs on the server thread (Bukkit API); encoding is cheap and
 * happens inline, network writes are dispatched asynchronously by the listener.
 */
public final class CaptureService {

    private final Logger logger;
    private final PaperConfig config;
    private final String serverId;
    private final Map<UUID, AtomicLong> captureSequences = new ConcurrentHashMap<>();

    public CaptureService(Logger logger, PaperConfig config) {
        this.logger = logger;
        this.config = config;
        this.serverId = config.serverId();
    }

    public SnapshotMessage capture(Player player, String cause) {
        return capture(player, cause, null);
    }

    public SnapshotMessage capture(Player player, String cause, ItemStack carriedOverride) {
        try {
            Map<Integer, byte[]> segments = new LinkedHashMap<>();
            PlayerInventory inventory = player.getInventory();
            if (config.enabled(Segment.INVENTORY)) {
                segments.put(Segment.INVENTORY, ItemStack.serializeItemsAsBytes(inventory.getContents()));
            }
            if (config.enabled(Segment.ENDER_CHEST)) {
                segments.put(Segment.ENDER_CHEST, ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents()));
            }
            if (config.enabled(Segment.CORE)) {
                segments.put(Segment.CORE, coreData(player).encode());
            }
            if (config.enabled(Segment.EFFECTS)) {
                segments.put(Segment.EFFECTS, effects(player));
            }
            if (config.enabled(Segment.ATTRIBUTES)) {
                segments.put(Segment.ATTRIBUTES, attributes(player));
            }
            if (config.enabled(Segment.PDC)) {
                segments.put(Segment.PDC, player.getPersistentDataContainer().serializeToBytes());
            }
            if (config.enabled(Segment.ADVANCEMENTS) && NmsBridge.dataBridgeAvailable()) {
                segments.put(Segment.ADVANCEMENTS, NmsBridge.captureAdvancements(player));
            }
            if (config.enabled(Segment.STATISTICS) && NmsBridge.dataBridgeAvailable()) {
                segments.put(Segment.STATISTICS, NmsBridge.captureStatistics(player));
            }
            if (config.enabled(Segment.MISC)) {
                segments.put(Segment.MISC, miscData(player, carriedOverride).encode());
            }

            SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, NmsBridge.dataVersion(), segments);
            long captureSeq = captureSequences
                    .computeIfAbsent(player.getUniqueId(), id -> new AtomicLong())
                    .incrementAndGet();
            return new SnapshotMessage(0L, captureSeq, cause, serverId, envelope.encode());
        } catch (Exception e) {
            logger.severe("Failed to capture snapshot for " + player.getName() + ": " + e);
            return null;
        }
    }

    private CoreData coreData(Player player) {
        double maxHealth = 20.0;
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttribute != null) {
            maxHealth = maxHealthAttribute.getValue();
        }
        return new CoreData(
                Math.min(player.getHealth(), maxHealth),
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getLevel(),
                player.getExp(),
                player.calculateTotalExperiencePoints(),
                player.getGameMode().name(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getFlySpeed(),
                player.getWalkSpeed(),
                player.getInventory().getHeldItemSlot(),
                player.getFireTicks(),
                player.getFreezeTicks(),
                player.getRemainingAir(),
                player.getFallDistance()
        );
    }

    private byte[] effects(Player player) {
        List<EffectList.Entry> entries = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            PotionEffectType type = effect.getType();
            entries.add(new EffectList.Entry(
                    type.getKey().toString(),
                    effect.getDuration(),
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.hasParticles(),
                    effect.hasIcon()
            ));
        }
        return EffectList.encode(entries);
    }

    private byte[] attributes(Player player) {
        List<AttributeList.Entry> entries = new ArrayList<>();
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                entries.add(new AttributeList.Entry(attribute.getKey().toString(), instance.getBaseValue()));
            }
        }
        return AttributeList.encode(entries);
    }

    private MiscData miscData(Player player, ItemStack override) {
        ItemStack carried = override != null ? override : NmsBridge.getCarriedItem(player);
        if (carried == null || carried.getType().isAir()) {
            return MiscData.EMPTY;
        }
        return new MiscData(true, carried.serializeAsBytes());
    }

    public void forget(UUID uuid) {
        captureSequences.remove(uuid);
    }
}
