package com.junxiex.mikudatasync.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Single place for reflective NMS access. M2 needs only the data version and the container
 * cursor item; statistics/advancements bridging lands in M2b.
 */
public final class NmsBridge {

    private static Logger logger = Logger.getLogger("MikuDataSync");
    private static Method craftGetHandle;
    private static Field containerMenuField;
    private static Method menuGetCarried;
    private static Method menuSetCarried;
    private static Method craftAsBukkitCopy;
    private static Method craftAsNMSCopy;
    private static boolean cursorAvailable;
    private static boolean initialized;

    private NmsBridge() {
    }

    public static synchronized void initialize(Logger pluginLogger) {
        if (initialized) {
            return;
        }
        initialized = true;
        logger = pluginLogger;
        try {
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");

            craftGetHandle = craftPlayer.getMethod("getHandle");
            containerMenuField = findField(serverPlayer, "containerMenu");
            Class<?> menuClass = containerMenuField.getType();
            menuGetCarried = menuClass.getMethod("getCarried");
            menuSetCarried = menuClass.getMethod("setCarried", nmsItemStack);
            craftAsBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);
            craftAsNMSCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            cursorAvailable = true;
            logger.info("NMS cursor bridge ready");
        } catch (Throwable t) {
            cursorAvailable = false;
            logger.warning("NMS cursor bridge unavailable: " + t);
        }
    }

    public static int dataVersion() {
        return Bukkit.getUnsafe().getDataVersion();
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

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException ignored) {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }
}
