package com.junxiex.mikudatasync.common;

/** Wire protocol constants shared by the proxy plugin and the backend plugin. */
public final class Protocol {

    /** "MDS1" */
    public static final int MAGIC = 0x4D445331;

    public static final int PROTOCOL_VERSION = 2;
    public static final int SCHEMA_VERSION = 2;

    /** Maximum size of one IPC frame (header included). */
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    /** Maximum size of one serialized snapshot envelope (compressed, as stored on disk). */
    public static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;

    /**
     * Maximum size of the uncompressed segment table inside one snapshot. Encode and decode must
     * use the same limit: an inner blob that encodes but cannot be decoded would strand a player
     * on a snapshot that is never readable again.
     */
    public static final int MAX_INNER_BYTES = 4 * 1024 * 1024;

    public static final int MAX_SEGMENT_BYTES = 512 * 1024;

    /** UTF-8 byte limit for SnapshotMessage.saveCause. */
    public static final int MAX_SAVE_CAUSE_BYTES = 64;

    /** UTF-8 byte limit for SnapshotMessage.sourceServer and the IPC handshake server id. */
    public static final int MAX_SERVER_ID_BYTES = 128;

    /** Secret shipped in both config.yml files; it is public, so it must be replaced on every install. */
    public static final String PLACEHOLDER_SECRET = "please-change-this-secret-to-a-random-32-byte-string";

    private Protocol() {
    }
}
