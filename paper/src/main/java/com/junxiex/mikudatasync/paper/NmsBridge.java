package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.AdvancementList;
import com.junxiex.mikudatasync.common.StatisticsList;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemMeta;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import com.junxiex.mikudatasync.common.ItemMapData;
import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.MapDataList;

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
    private static Object nmsEmptyStack;
    private static boolean cursorAvailable;

    // --- maps bridge ---
    private static boolean mapsAvailable;
    private static Field craftMapViewWorldMap;
    private static Field mapColorsField;
    private static Field mapScaleField;
    private static Field mapCenterXField;
    private static Field mapCenterZField;
    private static Field mapLockedField;
    private static Method mapSetDirty;
    private static Method craftWorldGetHandle;
    private static Method serverLevelGetFreeMapId;
    private static Method serverLevelSetMapData;
    private static Method serverLevelDimension;
    private static Method mapDataCreateFresh;
    private static Method mapIdId;
    private static java.lang.reflect.Constructor<?> mapIdNew;
    private static Method serverLevelGetMapData;
    private static String mapServerId = "unknown";
    private static MapBindings mapBindings;
    private static final NamespacedKey MAP_ITEM_KEY = NamespacedKey.fromString("mikudatasync:map");

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
    private static Object customStatRegistry;
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
        initialize(pluginLogger, "unknown", null);
    }

    public static synchronized void initialize(Logger pluginLogger, String serverId, MapBindings bindings) {
        mapServerId = serverId;
        mapBindings = bindings;
        if (initialized) {
            return;
        }
        initialized = true;
        logger = pluginLogger;
        initializeCursorBridge();
        initializeDataBridge();
        initializeMapBridge();
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

    // ---------------------------------------------------------------- maps

    private static void initializeMapBridge() {
        try {
            Class<?> craftMapView = Class.forName("org.bukkit.craftbukkit.map.CraftMapView");
            Class<?> mapData = Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData");
            craftMapViewWorldMap = craftMapView.getDeclaredField("worldMap");
            craftMapViewWorldMap.setAccessible(true);
            mapColorsField = findField(mapData, "colors");
            mapScaleField = findField(mapData, "scale");
            mapCenterXField = findField(mapData, "centerX");
            mapCenterZField = findField(mapData, "centerZ");
            mapLockedField = findField(mapData, "locked");
            try {
                mapSetDirty = Class.forName("net.minecraft.world.level.saveddata.SavedData")
                        .getMethod("setDirty");
            } catch (Throwable ignored) {
                mapSetDirty = null;
            }
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> mapId = Class.forName("net.minecraft.world.level.saveddata.maps.MapId");
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
            craftWorldGetHandle = craftWorld.getMethod("getHandle");
            serverLevelGetFreeMapId = serverLevel.getMethod("getFreeMapId");
            serverLevelSetMapData = serverLevel.getMethod("setMapData", mapId, mapData);
            serverLevelGetMapData = serverLevel.getMethod("getMapData", mapId);
            mapIdNew = mapId.getConstructor(int.class);
            serverLevelDimension = serverLevel.getMethod("dimension");
            mapDataCreateFresh = mapData.getMethod("createFresh", double.class, double.class, byte.class,
                    boolean.class, boolean.class, resourceKey);
            mapIdId = mapId.getMethod("id");
            mapsAvailable = true;
        } catch (Throwable t) {
            logger.warning("NMS map bridge unavailable: " + t);
            mapsAvailable = false;
        }
    }

    public static boolean mapBridgeAvailable() {
        return mapsAvailable;
    }

    public record MapInfo(int mapId, byte scale, int centerX, int centerZ, boolean locked, int checksum) {
    }

    public static MapInfo mapInfo(Player player) {
        if (!mapsAvailable || player == null) {
            return null;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir() || !(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapId()) {
            return null;
        }
        try {
            ItemMapData persisted = readItemMap(meta);
            if (persisted != null) {
                return new MapInfo(meta.getMapId(), persisted.scale(), persisted.centerX(),
                        persisted.centerZ(), persisted.locked(), checksum(persisted.colors()));
            }
            Object handle = mapData(player.getWorld(), meta.getMapId());
            if (handle == null) {
                return null;
            }
            byte[] colors = (byte[]) mapColorsField.get(handle);
            int checksum = colors == null ? 0 : checksum(colors);
            return new MapInfo(meta.getMapId(), (byte) mapScaleField.get(handle),
                    (int) mapCenterXField.get(handle), (int) mapCenterZField.get(handle),
                    (boolean) mapLockedField.get(handle), checksum);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean fillHeldMap(Player player, byte value) {
        if (!mapsAvailable || player == null) {
            return false;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir() || !(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapId()) {
            return false;
        }
        try {
            Object handle = mapData(player.getWorld(), meta.getMapId());
            if (handle == null) {
                return false;
            }
            byte[] colors = (byte[]) mapColorsField.get(handle);
            if (colors == null || colors.length != MapDataList.COLORS_LENGTH) {
                colors = new byte[MapDataList.COLORS_LENGTH];
            }
            java.util.Arrays.fill(colors, value);
            mapColorsField.set(handle, colors);
            markDirty(handle);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static byte[] captureMaps(Player player) {
        if (!mapsAvailable || player == null) {
            return null;
        }
        Map<Integer, MapDataList.Entry> maps = new java.util.LinkedHashMap<>();
        collectMaps(player, player.getInventory().getContents(), maps);
        collectMaps(player, player.getEnderChest().getContents(), maps);
        if (maps.isEmpty()) {
            return null;
        }
        List<MapDataList.Entry> entries = new ArrayList<>(maps.values());
        if (entries.size() > MapDataList.MAX_MAPS) {
            entries = entries.subList(0, MapDataList.MAX_MAPS);
        }
        return MapDataList.encode(entries);
    }

    private static void collectMaps(Player player, ItemStack[] items, Map<Integer, MapDataList.Entry> out) {
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || !(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapId()) {
                continue;
            }
            int id = meta.getMapId();
            if (out.containsKey(id)) {
                continue;
            }
            try {
                Object handle = mapData(player.getWorld(), id);
                if (handle == null) {
                    continue;
                }
                byte[] colors = (byte[]) mapColorsField.get(handle);
                if (colors == null || colors.length != MapDataList.COLORS_LENGTH) {
                    continue;
                }
                out.put(id, new MapDataList.Entry(id, (byte) mapScaleField.get(handle),
                        (int) mapCenterXField.get(handle), (int) mapCenterZField.get(handle),
                        (boolean) mapLockedField.get(handle), colors.clone()));
            } catch (Throwable ignored) {
            }
        }
    }

    public static void applyMaps(Player player, byte[] payload) throws Exception {
        if (!mapsAvailable || player == null || payload == null) {
            return;
        }
        List<MapDataList.Entry> entries = MapDataList.decode(payload);
        logger.info("MDS map apply: " + entries.size() + " maps, first checksum=" + (entries.isEmpty() ? 0 : checksum(entries.get(0).colors())));
        Map<Integer, Integer> mapped = new HashMap<>();
        for (MapDataList.Entry entry : entries) {
            MapView view = Bukkit.createMap(player.getWorld());
            Object handle = mapHandle(view);
            mapColorsField.set(handle, entry.colors().clone());
            mapScaleField.set(handle, entry.scale());
            mapCenterXField.set(handle, entry.centerX());
            mapCenterZField.set(handle, entry.centerZ());
            mapLockedField.set(handle, entry.locked());
            markDirty(handle);
            byte[] readBack = (byte[]) mapColorsField.get(handle);
            logger.info("MDS map apply: source=" + entry.mapId() + " target=" + view.getId()
                    + " checksum=" + checksum(readBack));
            mapped.put(entry.mapId(), view.getId());
        }
        rewriteMapItems(player.getInventory().getContents(), mapped, player.getInventory());
        rewriteMapItems(player.getEnderChest().getContents(), mapped, player.getEnderChest());
    }

    public static Map<Integer, Integer> createMaps(org.bukkit.World world, List<MapDataList.Entry> entries) throws Exception {
        Map<Integer, Integer> mapped = new HashMap<>();
        Object level = craftWorldGetHandle.invoke(world);
        Object dimension = serverLevelDimension.invoke(level);
        for (MapDataList.Entry entry : entries) {
            // Allocate and register the map through the server map index. Bukkit.createMap can race
            // the map registry on Folia (the saved data is loaded after creation and overwrites the
            // colours), so use the authoritative NMS path instead.
            Object mapId;
            int newId;
            int tries = 0;
            do {
                mapId = serverLevelGetFreeMapId.invoke(level);
                newId = (int) mapIdId.invoke(mapId);
            } while (serverLevelGetMapData.invoke(level, mapId) != null && ++tries < 4096);
            Object data = mapDataCreateFresh.invoke(null, (double) entry.centerX(), (double) entry.centerZ(),
                    entry.scale(), false, false, dimension);
            mapColorsField.set(data, entry.colors().clone());
            mapLockedField.set(data, entry.locked());
            serverLevelSetMapData.invoke(level, mapId, data);
            logger.info("MDS map create: source=" + entry.mapId() + " target=" + newId
                    + " checksum=" + checksum((byte[]) mapColorsField.get(data))
                    + " registered=" + checksum((byte[]) mapColorsField.get(mapData(world, newId))));
            mapped.put(entry.mapId(), newId);
        }
        return mapped;
    }

    /** Re-applies colours to already-created target maps (used to survive late map-data loads). */
    public static void reapplyMapColors(org.bukkit.World world, List<MapDataList.Entry> entries,
                                       Map<Integer, Integer> mapped) {
        for (MapDataList.Entry entry : entries) {
            Integer target = mapped.get(entry.mapId());
            if (target == null) {
                continue;
            }
            try {
                Object data = mapData(world, target);
                if (data == null) {
                    continue;
                }
                mapColorsField.set(data, entry.colors().clone());
                mapScaleField.set(data, entry.scale());
                mapCenterXField.set(data, entry.centerX());
                mapCenterZField.set(data, entry.centerZ());
                mapLockedField.set(data, entry.locked());
                markDirty(data);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void rewriteMapItems(Player player, Map<Integer, Integer> mapped) {
        if (mapped == null || mapped.isEmpty()) {
            return;
        }
        rewriteMapItems(player.getInventory().getContents(), mapped, player.getInventory());
        rewriteMapItems(player.getEnderChest().getContents(), mapped, player.getEnderChest());
    }

    private static void rewriteMapItems(ItemStack[] items, Map<Integer, Integer> mapped,
                                        org.bukkit.inventory.Inventory inventory) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (!(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapId()) {
                continue;
            }
            Integer newId = mapped.get(meta.getMapId());
            if (newId == null) {
                continue;
            }
            meta.setMapId(newId);
            item.setItemMeta(meta);
            inventory.setItem(i, item);
        }
    }

    private static void markDirty(Object handle) {
        if (mapSetDirty == null) {
            return;
        }
        try {
            mapSetDirty.invoke(handle);
        } catch (Throwable ignored) {
        }
    }

    private static Object mapHandle(MapView view) throws Exception {
        return craftMapViewWorldMap.get(view);
    }

    private static Object mapData(org.bukkit.World world, int id) throws Exception {
        Object level = craftWorldGetHandle.invoke(world);
        Object mapId = mapIdNew.newInstance(id);
        return serverLevelGetMapData.invoke(level, mapId);
    }

    private static int checksum(byte[] colors) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(colors);
        return (int) crc.getValue();
    }

    // ---------------------------------------------------------------- item maps

    public static void persistMaps(Player player) {
        if (!mapsAvailable || player == null) {
            return;
        }
        persistMaps(player, player.getInventory().getContents(), player.getInventory());
        persistMaps(player, player.getEnderChest().getContents(), player.getEnderChest());
    }

    private static void persistMaps(Player player, ItemStack[] items, org.bukkit.inventory.Inventory inventory) {
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            if (meta instanceof MapMeta mapMeta && mapMeta.hasMapId()) {
                persistSingleMap(player, item, mapMeta, inventory, i);
            } else if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta blockState
                    && blockState.getBlockState() instanceof org.bukkit.block.Container container
                    && !container.getInventory().isEmpty()) {
                persistMaps(player, container.getInventory().getContents(), container.getInventory());
                blockState.setBlockState(container);
                item.setItemMeta(blockState);
                inventory.setItem(i, item);
            } else if (meta instanceof org.bukkit.inventory.meta.BundleMeta bundle && bundle.hasItems()) {
                ItemStack[] nested = bundle.getItems().toArray(ItemStack[]::new);
                persistMaps(player, nested, null);
                bundle.setItems(java.util.List.of(nested));
                item.setItemMeta(bundle);
                inventory.setItem(i, item);
            }
        }
    }

    private static void persistSingleMap(Player player, ItemStack item, MapMeta meta,
                                         org.bukkit.inventory.Inventory inventory, int slot) {
        if (meta.getPersistentDataContainer().has(MAP_ITEM_KEY, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY)) {
            return;
        }
        MapView view = Bukkit.getMap(meta.getMapId());
        if (view == null) {
            return;
        }
        try {
            CapturingCanvas canvas = new CapturingCanvas(view);
            for (org.bukkit.map.MapRenderer renderer : view.getRenderers()) {
                renderer.render(view, canvas, player);
            }
            byte[] colors = canvas.pixels();
            java.util.List<ItemMapData.CursorData> cursors = new ArrayList<>();
            org.bukkit.map.MapCursorCollection collection = canvas.cursors();
            for (int c = 0; c < collection.size(); c++) {
                org.bukkit.map.MapCursor cursor = collection.getCursor(c);
                String caption = cursor.caption() == null ? null
                        : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(cursor.caption());
                cursors.add(new ItemMapData.CursorData(cursor.getX(), cursor.getY(), cursor.getDirection(),
                        cursor.getType().name(), caption));
            }
            byte scale = (byte) view.getScale().ordinal();
            int centerX = 0;
            int centerZ = 0;
            boolean locked = view.isLocked();
            try {
                Object data = mapDataFor(inventory, meta.getMapId());
                if (data != null) {
                    scale = (byte) mapScaleField.get(data);
                    centerX = (int) mapCenterXField.get(data);
                    centerZ = (int) mapCenterZField.get(data);
                    locked = (boolean) mapLockedField.get(data);
                }
            } catch (Throwable ignored) {
            }
            ItemMapData persisted = new ItemMapData(mapServerId, meta.getMapId(), scale, centerX, centerZ,
                    locked, colors.clone(), java.util.List.copyOf(cursors));
            meta.getPersistentDataContainer().set(MAP_ITEM_KEY,
                    org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, persisted.encode());
            item.setItemMeta(meta);
            if (inventory != null) {
                inventory.setItem(slot, item);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object mapDataFor(org.bukkit.inventory.Inventory inventory, int id) {
        try {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                Object data = mapData(world, id);
                if (data != null) {
                    return data;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void applyItemMaps(Player player, byte[] payload) {
        if (!mapsAvailable || player == null || payload == null) {
            return;
        }
        List<MapDataList.Entry> fallback;
        try {
            fallback = MapDataList.decode(payload);
        } catch (ProtocolException e) {
            return;
        }
        java.util.Map<Integer, MapDataList.Entry> byId = new HashMap<>();
        for (MapDataList.Entry entry : fallback) {
            byId.put(entry.mapId(), entry);
        }
        applyItemMaps(player, player.getInventory().getContents(), player.getInventory(), byId);
        applyItemMaps(player, player.getEnderChest().getContents(), player.getEnderChest(), byId);
    }

    private static void applyItemMaps(Player player, ItemStack[] items,
                                      org.bukkit.inventory.Inventory inventory,
                                      java.util.Map<Integer, MapDataList.Entry> fallback) {
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            if (meta instanceof MapMeta mapMeta && mapMeta.hasMapId()) {
                applySingleMap(item, mapMeta, inventory, i, fallback);
            } else if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta blockState
                    && blockState.getBlockState() instanceof org.bukkit.block.Container container
                    && !container.getInventory().isEmpty()) {
                applyItemMaps(player, container.getInventory().getContents(), container.getInventory(), fallback);
                blockState.setBlockState(container);
                item.setItemMeta(blockState);
                inventory.setItem(i, item);
            } else if (meta instanceof org.bukkit.inventory.meta.BundleMeta bundle && bundle.hasItems()) {
                ItemStack[] nested = bundle.getItems().toArray(ItemStack[]::new);
                applyItemMaps(player, nested, null, fallback);
                bundle.setItems(java.util.List.of(nested));
                item.setItemMeta(bundle);
                inventory.setItem(i, item);
            }
        }
    }

    private static void applySingleMap(ItemStack item, MapMeta meta, org.bukkit.inventory.Inventory inventory,
                                       int slot, java.util.Map<Integer, MapDataList.Entry> fallback) {
        try {
            ItemMapData persisted = readItemMap(meta);
            if (persisted == null) {
                MapDataList.Entry entry = fallback.get(meta.getMapId());
                if (entry == null) {
                    return;
                }
                persisted = new ItemMapData(mapServerId, entry.mapId(), entry.scale(),
                        entry.centerX(), entry.centerZ(), entry.locked(), entry.colors().clone());
            }
            int targetId = mapBindings == null ? -1
                    : mapBindings.get(persisted.originServer(), persisted.sourceMapId());
            MapView view = targetId >= 0 ? Bukkit.getMap(targetId) : null;
            if (view == null) {
                view = Bukkit.createMap(Bukkit.getWorlds().get(0));
                if (mapBindings != null) {
                    mapBindings.put(persisted.originServer(), persisted.sourceMapId(), view.getId());
                }
            }
            view.getRenderers().clear();
            view.addRenderer(new MdsMapRenderer(persisted.colors(), persisted.cursors()));
            view.setLocked(true);
            view.setScale(MapView.Scale.values()[Math.max(0, Math.min(4, persisted.scale()))]);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            meta.setMapId(view.getId());
            meta.getPersistentDataContainer().set(MAP_ITEM_KEY,
                    org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, persisted.encode());
            item.setItemMeta(meta);
            if (inventory != null) {
                inventory.setItem(slot, item);
            }
        } catch (Throwable ignored) {
        }
    }

    private static ItemMapData readItemMap(MapMeta meta) {
        byte[] bytes = meta.getPersistentDataContainer().get(MAP_ITEM_KEY,
                org.bukkit.persistence.PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            return null;
        }
        try {
            return ItemMapData.decode(bytes);
        } catch (ProtocolException e) {
            return null;
        }
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
            nmsEmptyStack = nmsItemStack.getField("EMPTY").get(null);
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
            // AbstractContainerMenu.carried must never be null in NMS; null causes an NPE on menu tick/close.
            Object nmsItem = item == null ? nmsEmptyStack : craftAsNMSCopy.invoke(null, item);
            menuSetCarried.invoke(menu, nmsItem);
        } catch (Throwable t) {
            logger.fine("Failed to restore cursor item: " + t);
        }
    }

    // ------------------------------------------------------- statistics

    public static byte[] captureStatistics(Player player) {
        if (!dataAvailable) {
            return null;
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
            return null;
        }
    }

    public static void applyStatistics(Player player, byte[] payload) throws Exception {
        if (!dataAvailable) {
            return;
        }
        Object handle = craftGetHandle.invoke(player);
        Object counter = serverPlayerStatsField.get(handle);
        Object existingRaw = statsCounterStatsField.get(counter);
        Map<?, ?> existingStats = existingRaw instanceof Map<?, ?> map ? map : null;
        if (existingStats != null) {
            for (Object stat : new ArrayList<>(existingStats.keySet())) {
                Object current = existingStats.get(stat);
                if (current instanceof Number number && number.intValue() == 0) {
                    continue;
                }
                try {
                    statsCounterSetValue.invoke(counter, handle, stat, 0);
                } catch (Throwable ignored) {
                }
            }
        }
        for (StatisticsList.Entry entry : StatisticsList.decode(payload)) {
            Object statType = registryGetValue.invoke(statTypeRegistry, identifier(entry.statTypeKey()));
            if (statType == null) {
                continue;
            }
            Object value;
            if (statType == statsCustomType) {
                // Custom stat values are Identifier instances registered in CUSTOM_STAT; use the
                // canonical instance, otherwise Registry#getKey returns null and Stat construction NPEs.
                value = registryGetValue.invoke(customStatRegistry, identifier(entry.valueKey()));
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
            if (stat == null) {
                continue;
            }
            if (existingStats != null) {
                Object current = existingStats.get(stat);
                if (current instanceof Number number && number.intValue() == entry.value()) {
                    continue;
                }
            }
            statsCounterSetValue.invoke(counter, handle, stat, entry.value());
        }
    }

    // ------------------------------------------------------ advancements

    public static byte[] captureAdvancements(Player player) {
        if (!dataAvailable) {
            return null;
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
            return null;
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
        List<AdvancementList.Entry> entries = AdvancementList.decode(payload);
        Set<String> desiredKeys = new HashSet<>();
        for (AdvancementList.Entry entry : entries) {
            desiredKeys.add(entry.advancementKey());
        }
        for (AdvancementList.Entry entry : entries) {
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
            Set<String> completed = new HashSet<>();
            Object currentRaw = advancementGetCompletedCriteria.invoke(progress);
            if (currentRaw instanceof Iterable<?> current) {
                for (Object criterion : current) {
                    completed.add(String.valueOf(criterion));
                }
            }
            if (strict) {
                for (String criterion : completed) {
                    if (!desired.contains(criterion)) {
                        advancementRevokeProgress.invoke(progress, criterion);
                    }
                }
            }
            for (String criterion : desired) {
                if (completed.contains(criterion)) {
                    continue;
                }
                try {
                    advancementGrantProgress.invoke(progress, criterion);
                } catch (Throwable ignored) {
                    // Criterion no longer exists (datapack change) - skip it.
                }
            }
        }
        if (strict) {
            // Revoke progress that exists only on the target server, so the snapshot is authoritative.
            Object mapRaw = playerAdvancementsProgressField.get(playerAdvancements);
            if (mapRaw instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> mapEntry : new ArrayList<>(map.entrySet())) {
                    Object holder = mapEntry.getKey();
                    Object progress = mapEntry.getValue();
                    Object id = advancementHolderId.invoke(holder);
                    if (id == null || desiredKeys.contains(id.toString()) || progress == null) {
                        continue;
                    }
                    Object completedRaw = advancementGetCompletedCriteria.invoke(progress);
                    if (completedRaw instanceof Iterable<?> completed) {
                        List<String> revoke = new ArrayList<>();
                        for (Object criterion : completed) {
                            revoke.add(String.valueOf(criterion));
                        }
                        for (String criterion : revoke) {
                            advancementRevokeProgress.invoke(progress, criterion);
                        }
                    }
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
            customStatRegistry = staticField(builtInRegistries, "CUSTOM_STAT");
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
