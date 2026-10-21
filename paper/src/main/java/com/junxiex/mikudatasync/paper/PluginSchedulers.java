package com.junxiex.mikudatasync.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Scheduler abstraction: BukkitScheduler on Paper, region/entity/async schedulers on Folia.
 */
public final class PluginSchedulers {

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private PluginSchedulers() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    public static void runOnEntity(Plugin plugin, Player player, Runnable action) {
        runOnEntityOrElse(plugin, player, action, null);
    }

    public static void runOnEntityOrElse(Plugin plugin, Player player, Runnable action, Runnable retired) {
        if (FOLIA) {
            player.getScheduler().run(plugin, task -> action.run(), retired);
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    public static void runDelayed(Plugin plugin, Player player, Runnable action, long delayTicks) {
        if (FOLIA) {
            player.getScheduler().runDelayed(plugin, task -> action.run(), null, Math.max(1L, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, action, Math.max(1L, delayTicks));
        }
    }

    public static void runAsync(Plugin plugin, Runnable action) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> action.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, action);
        }
    }

    public static void runTimer(Plugin plugin, Runnable action, long delayTicks, long periodTicks) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> action.run(),
                    Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, action, delayTicks, periodTicks);
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
