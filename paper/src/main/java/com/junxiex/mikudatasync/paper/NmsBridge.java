package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.AdvancementList;
import com.junxiex.mikudatasync.common.StatisticsList;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Single place for reflective NMS access (Mojang mappings as shipped by Paper).
 * M2 uses it for the container cursor item; M2b adds statistics and advancements.
 * All bridges are resolved once at enable and degrade to "unavailable" on failure.
 */
public final class NmsBridge {

    private static Logger logger = Logger.getLogger("MikuDataSync");
    private static boolean initialized;

    // --- cursor item bridge ---
    private static Method craftGetHandle;
    private static Field containerMenuField;
    private static Method menuGetCarried;
    private static Method menuSetCarried;
    private static Method craftAsBukkitCopy;
    private static Method craftAsNMSCopy;
    private static boolean cursorAvailable;

    // --- data bridge (statistics + advancements) ---
    private static boolean dataAvailable;
    private static Field serverPlayerStatsField;
    private static Field serverPlayerAdvancementsField;
    private static Field statsCounterStatsField;
    private static Method statsCounterSetValue;
    private static Method statGetType;
    private static Method statGetValue;
    private static Method statTypeGet;
    private static Method registryGetKey;
    private static Method registryGetValue;
    private static Method identifierParse;
    private static Class<?> identifierClass;
    private static Object statTypeRegistry;
    private static Object statsCustomType;
    private static final Map<Object, Object> statTypeRegistries = new HashMap<>();

    private static Field playerAdvancementsProgressField;
    private static Method advancementHolderId;
    private static Method advancementGetCompletedCriteria;
    private static Method advancementGrantProgress;
    private static Method advancementRevokeProgress;
    private static Method advancementGetOrStartProgress;
    private static Method craftAdvancementGetHandle;

    private NmsBridge() {
    }

    public static synchronized void initialize(Logger pluginLogger) {
        if (initialized) {
            return;
        }
        initialized = true;
        logger = pluginLogger;
        initializeCursorBridge();
        initializeDataBridge();
    }

    public static int dataVersion() {
        return Bukkit.getUnsafe().getDataVersion();
    }

    public static boolean cursorBridgeAvailable() {
        return cursorAvailable;
    }

    public static boolean dataBridgeAvailable() {
        return dataAvailable;
    }

    // ---------------------------------------------------------------- cursor

    private static void initializeCursorBridge() {
        try {
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> itemInstance = Class.forName("net.minecraft.world.item.ItemInstance");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");

            craftGetHandle = craftPlayer.getMethod("getHandle");
            containerMenuField = findField(serverPlayer, "containerMenu");
            Class<?> menuClass = containerMenuField.getType();
            menuGetCarried = menuClass.getMethod("getCarried");
            menuSetCarried = menuClass.getMethod("setCarried", nmsItemStack);
            craftAsBukkitCopy = craftItemStack.getMethod("asBukkitCopy", itemInstance);
            craftAsNMSCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            cursorAvailable = true;
            logger.info("NMS cursor bridge ready");
        } catch (Throwable t) {
            cursorAvailable = false;
            logger.warning("NMS cursor bridge unavailable: " + t);
        }
    }

    public static ItemStack getCarriedItem(Player player) {
        if (!cursorAvailable) {
            return null;
        }
        try {
            Object handle = craftGetHandle.invoke(player);
            Object menu = containerMenuField.get(handle);
            Object carried = menuGetCarried.invoke(menu);
            return (ItemStack) craftAsBukkitCopy.invoke(null, carried);
        } catch (Throwable t) {
            logger.fine("Failed to read cursor item: " + t);
            return null;
        }
    }

    public static void setCarriedItem(Player player, ItemStack item) {
        if (!cursorAvailable) {
            return;
        }
        try {
            Object handle = craftGetHandle.invoke(player);
            Object menu = containerMenuField.get(handle);
            Object nmsItem = item == null ? null : craftAsNMSCopy.invoke(null, item);
            menuSetCarried.invoke(menu, nmsItem);
        } catch (Throwable t) {
            logger.fine("Failed to restore cursor item: " + t);
        }
    }

    // ------------------------------------------------------- statistics

    public static byte[] captureStatistics(Player player) {
        if (!dataAvailable) {
            return StatisticsList.encode(List.of());
        }
        try {
            Object handle = craftGetHandle.invoke(player);
            Object counter = serverPlayerStatsField.get(handle);
            Object mapRaw = statsCounterStatsField.get(counter);
            if (!(mapRaw instanceof Map<?, ?> map)) {
                return StatisticsList.encode(List.of());
            }
            List<StatisticsList.Entry> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                Object stat = mapEntry.getKey();
                Object value = mapEntry.getValue();
                if (!(value instanceof Number number) || number.intValue() <= 0) {
                    continue;
                }
                Object type = statGetType.invoke(stat);
                Object statValue = statGetValue.invoke(stat);
                Object typeId = registryGetKey.invoke(statTypeRegistry, type);
                if (typeId == null) {
                    continue;
                }
                String valueKey = null;
                if (type == statsCustomType) {
                    valueKey = String.valueOf(statValue);
                } else {
                    Object registry = statTypeRegistries.get(type);
                    if (registry != null && statValue != null) {
                        Object valueId = registryGetKey.invoke(registry, statValue);
                        if (valueId != null) {
                            valueKey = valueId.toString();
                        }
                    }
                }
                entries.add(new StatisticsList.Entry(typeId.toString(), valueKey, number.intValue()));
            }
            return StatisticsList.encode(entries);
        } catch (Throwable t) {
            logger.warning("Failed to capture statistics: " + t);
            return StatisticsList.encode(List.of());
        }
    }

    public static void applyStatistics(Player player, byte[] payload) throws Exception {
        if (!dataAvailable) {
            return;
        }
        Object handle = craftGetHandle.invoke(player);
        Object counter = serverPlayerStatsField.get(handle);
        for (StatisticsList.Entry entry : StatisticsList.decode(payload)) {
            Object statType = registryGetValue.invoke(statTypeRegistry, identifier(entry.statTypeKey()));
            if (statType == null) {
                continue;
            }
            Object value;
            if (statType == statsCustomType) {
                value = identifier(entry.valueKey());
            } else {
                Object registry = statTypeRegistries.get(statType);
                if (registry == null || entry.valueKey() == null) {
                    continue;
                }
                value = registryGetValue.invoke(registry, identifier(entry.valueKey()));
            }
            if (value == null) {
                continue;
            }
            Object stat = statTypeGet.invoke(statType, value);
            if (stat != null) {
                statsCounterSetValue.invoke(counter, handle, stat, entry.value());
            }
        }
    }

    // ------------------------------------------------------ advancements

    public static byte[] captureAdvancements(Player player) {
        if (!dataAvailable) {
            return AdvancementList.encode(List.of());
        }
        try {
            Object handle = craftGetHandle.invoke(player);
            Object playerAdvancements = serverPlayerAdvancementsField.get(handle);
            Object mapRaw = playerAdvancementsProgressField.get(playerAdvancements);
            if (!(mapRaw instanceof Map<?, ?> map)) {
                return AdvancementList.encode(List.of());
            }
            List<AdvancementList.Entry> entries = new ArrayList<>();
            for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                Object holder = mapEntry.getKey();
                Object progress = mapEntry.getValue();
                Object id = advancementHolderId.invoke(holder);
                if (id == null || progress == null) {
                    continue;
                }
                Object completedRaw = advancementGetCompletedCriteria.invoke(progress);
                if (!(completedRaw instanceof Iterable<?> completed)) {
                    continue;
                }
                List<String> criteria = new ArrayList<>();
                for (Object criterion : completed) {
                    criteria.add(String.valueOf(criterion));
                }
                if (!criteria.isEmpty()) {
                    entries.add(new AdvancementList.Entry(id.toString(), criteria));
                }
            }
            return AdvancementList.encode(entries);
        } catch (Throwable t) {
            logger.warning("Failed to capture advancements: " + t);
            return AdvancementList.encode(List.of());
        }
    }

    /**
     * Writes criteria progress directly. This deliberately avoids Bukkit awardCriteria and the
     * vanilla award() path, which would re-run advancement rewards (loot/functions/recipes/XP).
     */
    public static void applyAdvancements(Player player, byte[] payload, boolean strict) throws Exception {
        if (!dataAvailable) {
            return;
        }
        Object handle = craftGetHandle.invoke(player);
        Object playerAdvancements = serverPlayerAdvancementsField.get(handle);
        for (AdvancementList.Entry entry : AdvancementList.decode(payload)) {
            NamespacedKey key = NamespacedKey.fromString(entry.advancementKey());
            if (key == null) {
                continue;
            }
            Advancement advancement = Bukkit.getAdvancement(key);
            if (advancement == null) {
                continue;
            }
            Object holder = craftAdvancementHandle(advancement);
            if (holder == null) {
                continue;
            }
            Object progress = advancementGetOrStartProgress.invoke(playerAdvancements, holder);
            Set<String> desired = new HashSet<>(entry.criteria());
            if (strict) {
                Object currentRaw = advancementGetCompletedCriteria.invoke(progress);
                if (currentRaw instanceof Iterable<?> current) {
                    List<String> revoke = new ArrayList<>();
                    for (Object criterion : current) {
                        String value = String.valueOf(criterion);
                        if (!desired.contains(value)) {
                            revoke.add(value);
                        }
                    }
                    for (String criterion : revoke) {
                        advancementRevokeProgress.invoke(progress, criterion);
                    }
                }
            }
            for (String criterion : desired) {
                try {
                    advancementGrantProgress.invoke(progress, criterion);
                } catch (Throwable ignored) {
                    // Criterion no longer exists (datapack change) - skip it.
                }
            }
        }
    }

    private static Object craftAdvancementHandle(Advancement advancement) {
        try {
            if (craftAdvancementGetHandle == null) {
                craftAdvancementGetHandle = advancement.getClass().getMethod("getHandle");
                craftAdvancementGetHandle.setAccessible(true);
            }
            return craftAdvancementGetHandle.invoke(advancement);
        } catch (Throwable t) {
            logger.fine("CraftAdvancement#getHandle unavailable: " + t);
            return null;
        }
    }

    // ----------------------------------------------------------- reflection

    private static void initializeDataBridge() {
        try {
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player");
            Class<?> statClass = Class.forName("net.minecraft.stats.Stat");
            Class<?> statTypeClass = Class.forName("net.minecraft.stats.StatType");
            Class<?> statsCounterClass = Class.forName("net.minecraft.stats.ServerStatsCounter");
            Class<?> statsClass = Class.forName("net.minecraft.stats.Stats");
            Class<?> builtInRegistries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Class<?> registryClass = Class.forName("net.minecraft.core.Registry");
            identifierClass = Class.forName("net.minecraft.resources.Identifier");

            serverPlayerStatsField = findField(serverPlayer, "stats");
            serverPlayerAdvancementsField = findField(serverPlayer, "advancements");
            statsCounterStatsField = findField(statsCounterClass, "stats");
            statsCounterSetValue = statsCounterClass.getMethod("setValue", playerClass, statClass, int.class);
            statGetType = statClass.getMethod("getType");
            statGetValue = statClass.getMethod("getValue");
            statTypeGet = statTypeClass.getMethod("get", Object.class);
            registryGetKey = registryClass.getMethod("getKey", Object.class);
            registryGetValue = registryClass.getMethod("getValue", identifierClass);
            identifierParse = resolveIdentifierParse();

            statTypeRegistry = staticField(builtInRegistries, "STAT_TYPE");
            statsCustomType = staticField(statsClass, "CUSTOM");
            addStatType(statsClass, builtInRegistries, "ITEM_USED", "ITEM");
            addStatType(statsClass, builtInRegistries, "ITEM_BROKEN", "ITEM");
            addStatType(statsClass, builtInRegistries, "ITEM_CRAFTED", "ITEM");
            addStatType(statsClass, builtInRegistries, "ITEM_PICKED_UP", "ITEM");
            addStatType(statsClass, builtInRegistries, "ITEM_DROPPED", "ITEM");
            addStatType(statsClass, builtInRegistries, "BLOCK_MINED", "BLOCK");
            addStatType(statsClass, builtInRegistries, "ENTITY_KILLED", "ENTITY_TYPE");
            addStatType(statsClass, builtInRegistries, "ENTITY_KILLED_BY", "ENTITY_TYPE");

            Class<?> playerAdvancementsClass = Class.forName("net.minecraft.server.PlayerAdvancements");
            Class<?> advancementHolderClass = Class.forName("net.minecraft.advancements.AdvancementHolder");
            Class<?> advancementProgressClass = Class.forName("net.minecraft.advancements.AdvancementProgress");
            playerAdvancementsProgressField = findField(playerAdvancementsClass, "progress");
            advancementHolderId = advancementHolderClass.getMethod("id");
            advancementGetCompletedCriteria = advancementProgressClass.getMethod("getCompletedCriteria");
            advancementGrantProgress = advancementProgressClass.getMethod("grantProgress", String.class);
            advancementRevokeProgress = advancementProgressClass.getMethod("revokeProgress", String.class);
            advancementGetOrStartProgress = playerAdvancementsClass.getMethod("getOrStartProgress", advancementHolderClass);

            dataAvailable = true;
            logger.info("NMS statistics/advancement bridge ready");
        } catch (Throwable t) {
            dataAvailable = false;
            logger.warning("NMS statistics/advancement bridge unavailable: " + t);
        }
    }

    private static void addStatType(Class<?> statsClass, Class<?> builtInRegistries, String typeField, String registryField) {
        try {
            Object type = staticField(statsClass, typeField);
            Object registry = staticField(builtInRegistries, registryField);
            if (type != null && registry != null) {
                statTypeRegistries.put(type, registry);
            }
        } catch (Throwable t) {
            logger.fine("Stat type " + typeField + " unavailable: " + t);
        }
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        Field field = type.getField(name);
        return field.get(null);
    }

    private static Method resolveIdentifierParse() {
        for (String name : new String[] {"parse", "tryParse"}) {
            try {
                return identifierClass.getMethod(name, String.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        logger.fine("Identifier#parse unavailable; using constructor fallback");
        return null;
    }

    private static Object identifier(String value) throws Exception {
        if (value == null) {
            return null;
        }
        if (identifierParse != null) {
            return identifierParse.invoke(null, value);
        }
        return identifierClass.getConstructor(String.class).newInstance(value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // continue with superclass
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }
}
