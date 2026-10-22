package com.junxiex.mikudatasync.paper.command;

import com.junxiex.mikudatasync.paper.MikuDataSyncPaper;
import com.junxiex.mikudatasync.common.ItemMapData;
import com.junxiex.mikudatasync.common.MapDataList;
import com.junxiex.mikudatasync.paper.MdsMapRenderer;
import com.junxiex.mikudatasync.paper.NmsBridge;
import com.junxiex.mikudatasync.paper.PaperConfig;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Admin-only debug helpers, disabled unless debug.enabled is true. */
public final class DebugCommand {

    private static final List<PersistentDataType<?, ?>> TYPES = List.of(
            PersistentDataType.STRING, PersistentDataType.INTEGER, PersistentDataType.LONG,
            PersistentDataType.DOUBLE, PersistentDataType.FLOAT, PersistentDataType.BYTE,
            PersistentDataType.SHORT, PersistentDataType.BYTE_ARRAY,
            PersistentDataType.INTEGER_ARRAY, PersistentDataType.LONG_ARRAY);

    private DebugCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> node(MikuDataSyncPaper plugin, PaperConfig config) {
        return Commands.literal("debug")
                .then(Commands.literal("info").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    if (!enabled(sender, config)) {
                        return 0;
                    }
                    sender.sendMessage(Component.text("debug | cursor=" + NmsBridge.cursorBridgeAvailable()
                            + " data=" + NmsBridge.dataBridgeAvailable()
                            + " dataVersion=" + NmsBridge.dataVersion()
                            + " debugEnabled=" + config.debugEnabled()));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("pdc")
                        .then(Commands.literal("set")
                                .then(Commands.argument("payload", StringArgumentType.greedyString())
                                        .executes(context -> pdcSet(context.getSource().getSender(), config,
                                                StringArgumentType.getString(context, "payload")))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(context -> pdcGet(context.getSource().getSender(), config,
                                                StringArgumentType.getString(context, "key").trim()))))
                        .then(Commands.literal("clear").executes(context -> {
                            CommandSender sender = context.getSource().getSender();
                            if (!enabled(sender, config)) {
                                return 0;
                            }
                            if (!(sender instanceof Player player)) {
                                sender.sendMessage(Component.text("players only"));
                                return 0;
                            }
                            PersistentDataContainer pdc = player.getPersistentDataContainer();
                            for (NamespacedKey key : pdc.getKeys()) {
                                pdc.remove(key);
                            }
                            sender.sendMessage(Component.text("player PDC cleared"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("map")
                        .then(Commands.literal("info").executes(context -> mapInfo(context.getSource().getSender(), config)))
                        .then(Commands.literal("init").executes(context -> mapInit(context.getSource().getSender(), config)))
                        .then(Commands.literal("shulker").executes(context -> mapShulker(context.getSource().getSender(), config)))
                        .then(Commands.literal("inspect").executes(context -> mapInspect(context.getSource().getSender(), config)))
                        .then(Commands.literal("selftest").executes(context -> {
                            if (context.getSource().getSender() instanceof Player player) {
                                context.getSource().getSender().sendMessage(Component.text(NmsBridge.mapSelfTest(player)));
                                return Command.SINGLE_SUCCESS;
                            }
                            return 0;
                        }))
                        .then(Commands.literal("fill")
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(context -> mapFill(context.getSource().getSender(), config,
                                                StringArgumentType.getString(context, "value"))))))
                .then(Commands.literal("workload")
                        .then(Commands.literal("pdc")
                                .then(Commands.argument("kb", StringArgumentType.word())
                                        .executes(context -> workloadPdc(context.getSource().getSender(), config,
                                                StringArgumentType.getString(context, "kb")))))
                        .then(Commands.literal("fill")
                                .then(Commands.argument("stacks", StringArgumentType.word())
                                        .then(Commands.argument("kb", StringArgumentType.word())
                                                .executes(context -> workloadFill(context.getSource().getSender(), config,
                                                        StringArgumentType.getString(context, "stacks"),
                                                        StringArgumentType.getString(context, "kb")))))))
                .then(Commands.literal("item")
                        .then(Commands.literal("set")
                                .then(Commands.argument("payload", StringArgumentType.greedyString())
                                        .executes(context -> itemSet(context.getSource().getSender(), config,
                                                StringArgumentType.getString(context, "payload")))))
                        .then(Commands.literal("get").executes(context -> itemGet(context.getSource().getSender(), config)))
                        .then(Commands.literal("name")
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(context -> itemName(context.getSource().getSender(), config,
                                                StringArgumentType.getString(context, "value")))))
                        .then(Commands.literal("clear").executes(context -> itemClear(context.getSource().getSender(), config))))
                .build();
    }

    private static boolean enabled(CommandSender sender, PaperConfig config) {
        if (!config.debugEnabled()) {
            sender.sendMessage(Component.text("debug commands are disabled (debug.enabled: false)"));
            return false;
        }
        return true;
    }

    private static int pdcSet(CommandSender sender, PaperConfig config, String payload) {
        if (!enabled(sender, config)) {
            return 0;
        }
        String[] parts = payload.trim().split("\\s+", 3);
        if (parts.length < 3) {
            sender.sendMessage(Component.text("usage: pdc set <key> <type> <value>"));
            return 0;
        }
        String rawKey = parts[0];
        String type = parts[1];
        String value = parts[2];
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        NamespacedKey key = NamespacedKey.fromString(rawKey);
        if (key == null) {
            sender.sendMessage(Component.text("invalid key: " + rawKey));
            return 0;
        }
        try {
            setValue(player.getPersistentDataContainer(), key, type, value);
            sender.sendMessage(Component.text("player PDC " + rawKey + " = " + value + " (" + type + ")"));
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            sender.sendMessage(Component.text("failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int pdcGet(CommandSender sender, PaperConfig config, String rawKey) {
        if (!enabled(sender, config)) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        NamespacedKey key = NamespacedKey.fromString(rawKey);
        if (key == null) {
            sender.sendMessage(Component.text("invalid key: " + rawKey));
            return 0;
        }
        String found = getValue(player.getPersistentDataContainer(), key);
        sender.sendMessage(Component.text(found == null ? "player PDC has no key " + rawKey : "player PDC " + found));
        return Command.SINGLE_SUCCESS;
    }

    private static int itemSet(CommandSender sender, PaperConfig config, String payload) {
        if (!enabled(sender, config)) {
            return 0;
        }
        String[] parts = payload.trim().split("\\s+", 3);
        if (parts.length < 3) {
            sender.sendMessage(Component.text("usage: item set <key> <type> <value>"));
            return 0;
        }
        String rawKey = parts[0];
        String type = parts[1];
        String value = parts[2];
        ItemStack item = heldItem(sender);
        if (item == null) {
            return 0;
        }
        NamespacedKey key = NamespacedKey.fromString(rawKey);
        if (key == null) {
            sender.sendMessage(Component.text("invalid key: " + rawKey));
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            sender.sendMessage(Component.text("item has no meta"));
            return 0;
        }
        try {
            setValue(meta.getPersistentDataContainer(), key, type, value);
            item.setItemMeta(meta);
            sender.sendMessage(Component.text("item PDC " + rawKey + " = " + value + " (" + type + ")"));
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            sender.sendMessage(Component.text("failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int workloadPdc(CommandSender sender, PaperConfig config, String kbArg) {
        if (!enabled(sender, config)) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        try {
            int kb = Math.max(1, Math.min(64, Integer.parseInt(kbArg)));
            java.util.Random random = new java.util.Random(player.getUniqueId().getMostSignificantBits());
            char[] chars = new char[kb * 1024];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = (char) ('a' + random.nextInt(26));
            }
            NamespacedKey key = NamespacedKey.fromString("mikudatasync:workload");
            player.getPersistentDataContainer().set(key, PersistentDataType.STRING, new String(chars));
            sender.sendMessage(Component.text("workload PDC: " + kb + "KB"));
            return Command.SINGLE_SUCCESS;
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("kb must be a number"));
            return 0;
        }
    }

    private static int workloadFill(CommandSender sender, PaperConfig config, String stacksArg, String kbArg) {
        if (!enabled(sender, config)) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        try {
            int stacks = Math.max(1, Math.min(36, Integer.parseInt(stacksArg)));
            int kb = Math.max(1, Math.min(16, Integer.parseInt(kbArg)));
            java.util.Random random = new java.util.Random(player.getUniqueId().getMostSignificantBits());
            char[] chars = new char[kb * 1024];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = (char) ('a' + random.nextInt(26));
            }
            String payload = new String(chars);
            NamespacedKey key = NamespacedKey.fromString("mikudatasync:workload");
            ItemStack item = new ItemStack(Material.PAPER, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return 0;
            }
            meta.displayName(Component.text("workload"));
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, payload);
            item.setItemMeta(meta);
            for (int i = 0; i < stacks; i++) {
                player.getInventory().setItem(i, item.clone());
            }
            sender.sendMessage(Component.text("workload: " + stacks + " stacks x " + kb + "KB PDC"));
            return Command.SINGLE_SUCCESS;
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("stacks/kb must be numbers"));
            return 0;
        }
    }

    private static int mapShulker(CommandSender sender, PaperConfig config) {
        if (!enabled(sender, config)) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        try {
            MapView view = org.bukkit.Bukkit.createMap(player.getWorld());
            byte[] colors = new byte[MapDataList.COLORS_LENGTH];
            java.util.Arrays.fill(colors, (byte) 7);
            view.getRenderers().clear();
            view.addRenderer(new MdsMapRenderer(colors, java.util.List.of(
                    new ItemMapData.CursorData((byte) 10, (byte) 20, (byte) 0, "BANNER_RED", "test"))));
            view.setLocked(true);
            ItemStack map = new ItemStack(Material.FILLED_MAP);
            MapMeta mapMeta = (MapMeta) map.getItemMeta();
            if (mapMeta == null) {
                return 0;
            }
            mapMeta.setMapId(view.getId());
            map.setItemMeta(mapMeta);
            ItemStack shulker = new ItemStack(Material.SHULKER_BOX);
            BlockStateMeta blockMeta = (BlockStateMeta) shulker.getItemMeta();
            if (blockMeta == null || !(blockMeta.getBlockState() instanceof ShulkerBox box)) {
                return 0;
            }
            box.getInventory().setItem(0, map);
            blockMeta.setBlockState(box);
            shulker.setItemMeta(blockMeta);
            player.getInventory().addItem(shulker);
            sender.sendMessage(Component.text("shulker map created id=" + view.getId()));
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            sender.sendMessage(Component.text("failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int mapInspect(CommandSender sender, PaperConfig config) {
        if (!enabled(sender, config)) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        java.util.List<String> lines = new ArrayList<>();
        inspectMapItems(player.getInventory().getContents(), lines, 0);
        inspectMapItems(player.getEnderChest().getContents(), lines, 0);
        sender.sendMessage(Component.text(lines.isEmpty() ? "no maps found" : String.join(" | ", lines)));
        return Command.SINGLE_SUCCESS;
    }

    private static void inspectMapItems(ItemStack[] items, java.util.List<String> out, int depth) {
        if (items == null) {
            return;
        }
        NamespacedKey key = NamespacedKey.fromString("mikudatasync:map");
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            if (meta instanceof MapMeta mapMeta && mapMeta.hasMapId()) {
                byte[] bytes = meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
                if (bytes == null) {
                    out.add("depth=" + depth + " id=" + mapMeta.getMapId() + " no-persisted-data");
                } else {
                    try {
                        ItemMapData data = ItemMapData.decode(bytes);
                        out.add("depth=" + depth + " id=" + mapMeta.getMapId()
                                + " checksum=" + checksum(data.colors()) + " cursors=" + data.cursors().size());
                    } catch (Exception e) {
                        out.add("depth=" + depth + " id=" + mapMeta.getMapId() + " decode-failed");
                    }
                }
            } else if (meta instanceof BlockStateMeta blockMeta
                    && blockMeta.getBlockState() instanceof org.bukkit.block.Container container) {
                inspectMapItems(container.getInventory().getContents(), out, depth + 1);
            } else if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
                inspectMapItems(bundle.getItems().toArray(ItemStack[]::new), out, depth + 1);
            }
        }
    }

    private static int checksum(byte[] colors) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(colors);
        return (int) crc.getValue();
    }

    private static int mapInit(CommandSender sender, PaperConfig config) {
        if (!enabled(sender, config)) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        var item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir() || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.MapMeta meta)) {
            sender.sendMessage(Component.text("hold a map item in the main hand"));
            return 0;
        }
        var view = org.bukkit.Bukkit.createMap(player.getWorld());
        meta.setMapId(view.getId());
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
        sender.sendMessage(Component.text("map initialised id=" + view.getId()));
        return Command.SINGLE_SUCCESS;
    }

    private static int mapInfo(CommandSender sender, PaperConfig config) {
        if (!enabled(sender, config)) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        var info = NmsBridge.mapInfo(player);
        sender.sendMessage(Component.text(info == null ? "no filled map in main hand"
                : "map id=" + info.mapId() + " scale=" + info.scale() + " center=" + info.centerX() + ","
                + info.centerZ() + " locked=" + info.locked() + " checksum=" + info.checksum()));
        return Command.SINGLE_SUCCESS;
    }

    private static int mapFill(CommandSender sender, PaperConfig config, String value) {
        if (!enabled(sender, config)) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return 0;
        }
        try {
            byte color = Byte.parseByte(value);
            boolean ok = NmsBridge.fillHeldMap(player, color);
            sender.sendMessage(Component.text(ok ? "map filled with " + color : "no filled map in main hand"));
            return ok ? Command.SINGLE_SUCCESS : 0;
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("value must be a byte -128..127"));
            return 0;
        }
    }

    private static int itemGet(CommandSender sender, PaperConfig config) {
        if (!enabled(sender, config)) {
            return 0;
        }
        ItemStack item = heldItem(sender);
        if (item == null) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        sender.sendMessage(Component.text("item=" + item.getType() + " x" + item.getAmount()
                + " name=" + (meta == null || meta.displayName() == null ? "-" : meta.displayName())
                + " lore=" + (meta == null ? 0 : meta.lore() == null ? 0 : meta.lore().size())
                + " enchants=" + (meta == null || meta.getEnchants().isEmpty() ? 0 : meta.getEnchants().size())));
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            List<String> keys = new ArrayList<>();
            for (NamespacedKey key : pdc.getKeys()) {
                String found = getValue(pdc, key);
                keys.add(key + "=" + (found == null ? "?" : found));
            }
            sender.sendMessage(Component.text("item PDC: " + (keys.isEmpty() ? "<empty>" : String.join(", ", keys))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int itemName(CommandSender sender, PaperConfig config, String value) {
        if (!enabled(sender, config)) {
            return 0;
        }
        ItemStack item = heldItem(sender);
        if (item == null) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            sender.sendMessage(Component.text("item has no meta"));
            return 0;
        }
        meta.displayName(Component.text(value));
        item.setItemMeta(meta);
        sender.sendMessage(Component.text("item name set to " + value));
        return Command.SINGLE_SUCCESS;
    }

    private static int itemClear(CommandSender sender, PaperConfig config) {
        if (!enabled(sender, config)) {
            return 0;
        }
        ItemStack item = heldItem(sender);
        if (item == null) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                pdc.remove(key);
            }
            item.setItemMeta(meta);
        }
        sender.sendMessage(Component.text("item PDC cleared"));
        return Command.SINGLE_SUCCESS;
    }

    private static ItemStack heldItem(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("players only"));
            return null;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            sender.sendMessage(Component.text("hold an item in the main hand"));
            return null;
        }
        return item;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setValue(PersistentDataContainer pdc, NamespacedKey key, String type, String raw) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "string" -> pdc.set(key, PersistentDataType.STRING, raw);
            case "int" -> pdc.set(key, PersistentDataType.INTEGER, Integer.parseInt(raw.trim()));
            case "long" -> pdc.set(key, PersistentDataType.LONG, Long.parseLong(raw.trim()));
            case "double" -> pdc.set(key, PersistentDataType.DOUBLE, Double.parseDouble(raw.trim()));
            case "float" -> pdc.set(key, PersistentDataType.FLOAT, Float.parseFloat(raw.trim()));
            case "byte" -> pdc.set(key, PersistentDataType.BYTE, Byte.parseByte(raw.trim()));
            case "short" -> pdc.set(key, PersistentDataType.SHORT, Short.parseShort(raw.trim()));
            case "byte_array" -> pdc.set(key, PersistentDataType.BYTE_ARRAY, parseBytes(raw));
            case "int_array" -> pdc.set(key, PersistentDataType.INTEGER_ARRAY, parseInts(raw));
            case "long_array" -> pdc.set(key, PersistentDataType.LONG_ARRAY, parseLongs(raw));
            default -> throw new IllegalArgumentException("type must be string/int/long/double/float/byte/short/byte_array/int_array/long_array");
        }
    }

    private static String getValue(PersistentDataContainer pdc, NamespacedKey key) {
        for (PersistentDataType<?, ?> type : TYPES) {
            try {
                Object value = pdc.get(key, (PersistentDataType) type);
                if (value != null) {
                    return "[" + typeName(type) + "] " + valueToString(value);
                }
            } catch (RuntimeException ignored) {
                // type mismatch, try the next one
            }
        }
        return null;
    }

    private static String typeName(PersistentDataType<?, ?> type) {
        if (type == PersistentDataType.STRING) return "string";
        if (type == PersistentDataType.INTEGER) return "int";
        if (type == PersistentDataType.LONG) return "long";
        if (type == PersistentDataType.DOUBLE) return "double";
        if (type == PersistentDataType.FLOAT) return "float";
        if (type == PersistentDataType.BYTE) return "byte";
        if (type == PersistentDataType.SHORT) return "short";
        if (type == PersistentDataType.BYTE_ARRAY) return "byte_array";
        if (type == PersistentDataType.INTEGER_ARRAY) return "int_array";
        if (type == PersistentDataType.LONG_ARRAY) return "long_array";
        return "?";
    }

    private static String valueToString(Object value) {
        if (value instanceof byte[] bytes) return java.util.Arrays.toString(bytes);
        if (value instanceof int[] ints) return java.util.Arrays.toString(ints);
        if (value instanceof long[] longs) return java.util.Arrays.toString(longs);
        return String.valueOf(value);
    }

    private static byte[] parseBytes(String raw) {
        String[] parts = raw.split(",");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Byte.parseByte(parts[i].trim());
        return out;
    }

    private static int[] parseInts(String raw) {
        String[] parts = raw.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private static long[] parseLongs(String raw) {
        String[] parts = raw.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Long.parseLong(parts[i].trim());
        return out;
    }
}