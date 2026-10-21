package com.junxiex.mikudatasync.common;

/** Snapshot segment identifiers. */
public final class Segment {

    public static final int CORE = 1;
    public static final int INVENTORY = 2;
    public static final int ENDER_CHEST = 3;
    public static final int EFFECTS = 4;
    public static final int ATTRIBUTES = 5;
    public static final int ADVANCEMENTS = 6;
    public static final int STATISTICS = 7;
    public static final int PDC = 8;
    public static final int RECIPES = 9;
    public static final int LOCATION = 10;
    public static final int MISC = 11;
    public static final int MAPS = 12;

    private Segment() {
    }

    /**
     * Critical segments must be applied atomically: if any of them fails to decode the whole
     * snapshot is rejected. Optional segments (recipes/location) may be skipped with a warning.
     */
    public static boolean isCritical(int id) {
        return id == CORE
                || id == INVENTORY
                || id == ENDER_CHEST
                || id == EFFECTS
                || id == ATTRIBUTES
                || id == ADVANCEMENTS
                || id == STATISTICS
                || id == PDC
                || id == MISC;
    }

    public static String name(int id) {
        return switch (id) {
            case CORE -> "core";
            case INVENTORY -> "inventory";
            case ENDER_CHEST -> "ender-chest";
            case EFFECTS -> "effects";
            case ATTRIBUTES -> "attributes";
            case ADVANCEMENTS -> "advancements";
            case STATISTICS -> "statistics";
            case PDC -> "pdc";
            case RECIPES -> "recipes";
            case LOCATION -> "location";
            case MISC -> "misc";
            case MAPS -> "maps";
            default -> "unknown(" + id + ")";
        };
    }
}
