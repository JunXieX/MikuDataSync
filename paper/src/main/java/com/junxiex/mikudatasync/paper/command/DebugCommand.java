package com.junxiex.mikudatasync.paper.command;

import com.junxiex.mikudatasync.paper.MikuDataSyncPaper;
import com.junxiex.mikudatasync.paper.NmsBridge;
import com.junxiex.mikudatasync.paper.PaperConfig;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(context -> pdcSet(context.getSource().getSender(), config,
                                                                StringArgumentType.getString(context, "key"),
                                                                StringArgumentType.getString(context, "type"),
                                                                StringArgumentType.getString(context, "value")))))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(context -> pdcGet(context.getSource().getSender(), config,
                                                StringArgumentType.getString(context, "key")))))
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
                .then(Commands.literal("item")
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(context -> itemSet(context.getSource().getSender(), config,
                                                                StringArgumentType.getString(context, "key"),
                                                                StringArgumentType.getString(context, "type"),
                                                                StringArgumentType.getString(context, "value")))))))
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

    private static int pdcSet(CommandSender sender, PaperConfig config, String rawKey, String type, String value) {
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

    private static int itemSet(CommandSender sender, PaperConfig config, String rawKey, String type, String value) {
        if (!enabled(sender, config)) {
            return 0;
        }
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
