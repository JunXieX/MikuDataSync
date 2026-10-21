package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.AttributeList;
import com.junxiex.mikudatasync.common.CoreData;
import com.junxiex.mikudatasync.common.EffectList;
import com.junxiex.mikudatasync.common.Metrics;
import org.bukkit.Bukkit;
import com.junxiex.mikudatasync.common.MiscData;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.StageMetrics;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.paper.api.MikuDataSyncCaptureEvent;
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

    public boolean capturesCursor() {
        return config.enabled(Segment.MISC);
    }

    public SnapshotMessage capture(Player player, String cause) {
        return capture(player, cause, null);
    }

    public SnapshotMessage capture(Player player, String cause, ItemStack carriedOverride) {
        long start = System.nanoTime();
        try {
            Map<Integer, byte[]> segments = new LinkedHashMap<>();
            PlayerInventory inventory = player.getInventory();
            if (config.enabled(Segment.INVENTORY)) {
                segments.put(Segment.INVENTORY, timed("capture.inventory", () -> ItemStack.serializeItemsAsBytes(inventory.getContents())));
            }
            if (config.enabled(Segment.ENDER_CHEST)) {
                segments.put(Segment.ENDER_CHEST, timed("capture.ender", () -> ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents())));
            }
            if (config.enabled(Segment.CORE)) {
                segments.put(Segment.CORE, timed("capture.core", () -> coreData(player).encode()));
            }
            if (config.enabled(Segment.EFFECTS)) {
                segments.put(Segment.EFFECTS, timed("capture.effects", () -> effects(player)));
            }
            if (config.enabled(Segment.ATTRIBUTES)) {
                segments.put(Segment.ATTRIBUTES, timed("capture.attributes", () -> attributes(player)));
            }
            if (config.enabled(Segment.PDC)) {
                segments.put(Segment.PDC, timed("capture.pdc", () -> player.getPersistentDataContainer().serializeToBytes()));
            }
            if (config.enabled(Segment.ADVANCEMENTS) && NmsBridge.dataBridgeAvailable()) {
                byte[] advancements = timed("capture.advancements", () -> NmsBridge.captureAdvancements(player));
                if (advancements != null) {
                    segments.put(Segment.ADVANCEMENTS, advancements);
                }
            }
            if (config.enabled(Segment.STATISTICS) && NmsBridge.dataBridgeAvailable()) {
                byte[] statistics = timed("capture.statistics", () -> NmsBridge.captureStatistics(player));
                if (statistics != null) {
                    segments.put(Segment.STATISTICS, statistics);
                }
            }
            if (config.enabled(Segment.MAPS) && NmsBridge.mapBridgeAvailable()) {
                byte[] maps = NmsBridge.captureMaps(player);
                if (maps != null) {
                    segments.put(Segment.MAPS, maps);
                }
            }
            if (config.enabled(Segment.MISC)) {
                segments.put(Segment.MISC, timed("capture.misc", () -> miscData(player, carriedOverride).encode()));
            }

            SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, NmsBridge.dataVersion(), segments);
            long captureSeq = captureSequences
                    .computeIfAbsent(player.getUniqueId(), id -> new AtomicLong())
                    .incrementAndGet();
            Metrics.recordCapture(System.nanoTime() - start);
            if (config.logTimings()) {
                logger.fine("capture " + cause + " took " + (System.nanoTime() - start) / 1_000_000.0 + "ms");
            }
            long encodeStart = System.nanoTime();
            byte[] encoded;
            try {
                encoded = envelope.encode();
            } finally {
                StageMetrics.record("capture.encode", System.nanoTime() - encodeStart);
            }
            SnapshotMessage message = new SnapshotMessage(0L, captureSeq, cause, serverId, encoded);
            Bukkit.getPluginManager().callEvent(new MikuDataSyncCaptureEvent(player, cause, message));
            return message;
        } catch (Exception e) {
            Metrics.recordCapture(System.nanoTime() - start);
            logger.severe("Failed to capture snapshot for " + player.getName() + ": " + e);
            return null;
        }
    }

    @FunctionalInterface
    private interface BytesSupplier {
        byte[] get() throws Exception;
    }

    private static byte[] timed(String stage, BytesSupplier supplier) throws Exception {
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            StageMetrics.record(stage, System.nanoTime() - start);
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
