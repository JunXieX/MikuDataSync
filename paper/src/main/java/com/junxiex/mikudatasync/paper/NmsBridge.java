package com.junxiex.mikudatasync.paper;

import java.util.logging.Logger;

/**
 * Single place for reflective NMS access. M1 only performs a placeholder self-check; the
 * statistics/advancements/cursor-item and attribute bridges land in M2.
 */
public final class NmsBridge {

    private static boolean initialized;

    private NmsBridge() {
    }

    public static synchronized void initialize(Logger logger) {
        if (initialized) {
            return;
        }
        initialized = true;
        logger.info("NMS bridge ready (M1 placeholder; data codecs land in M2)");
    }
}
