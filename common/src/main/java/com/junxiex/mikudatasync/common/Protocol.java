package com.junxiex.mikudatasync.common;

/** Wire protocol constants shared by the proxy plugin and the backend plugin. */
public final class Protocol {

    /** "MDS1" */
    public static final int MAGIC = 0x4D445331;

    public static final int PROTOCOL_VERSION = 1;
    public static final int SCHEMA_VERSION = 1;

    /** Maximum size of one IPC frame (header included). */
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    /** Maximum size of one serialized snapshot envelope. */
    public static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;

    public static final int MAX_SEGMENT_BYTES = 512 * 1024;

    private Protocol() {
    }
}
