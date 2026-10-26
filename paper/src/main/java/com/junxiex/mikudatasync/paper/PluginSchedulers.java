package com.junxiex.mikudatasync.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Scheduler abstraction: BukkitScheduler on Paper, region/entity/async schedulers on Folia.
 */
public final class PluginSchedulers {

    private static final boolean FOLIA = detectFolia();

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
        runDelayed(plugin, player, action, null, delayTicks);
    }

    public static void runDelayed(Plugin plugin, Player player, Runnable action, Runnable retired, long delayTicks) {
        if (FOLIA) {
            player.getScheduler().runDelayed(plugin, task -> action.run(), retired, Math.max(1L, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, action, Math.max(1L, delayTicks));
        }
    }

    public static void runOnGlobal(Plugin plugin, Runnable action) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, action);
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
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

    /**
     * Folia detection through the brand API, with the old marker class as a fallback. Relying on the
     * internal class name alone silently reported "Paper" when the class moved, and the resulting
     * BukkitScheduler calls throw on Folia.
     */
    private static boolean detectFolia() {
        try {
            io.papermc.paper.ServerBuildInfo buildInfo = io.papermc.paper.ServerBuildInfo.buildInfo();
            if (buildInfo.isBrandCompatible(net.kyori.adventure.key.Key.key("papermc", "folia"))) {
                return true;
            }
        } catch (Throwable ignored) {
            // Older or non-Paper runtime: fall back to the marker class.
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
