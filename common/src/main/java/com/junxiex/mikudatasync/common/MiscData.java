package com.junxiex.mikudatasync.common;

/** MISC segment: cursor item currently held on the container cursor (may be absent). */
public record MiscData(boolean hasCursorItem, byte[] cursorItem) {

    public static final MiscData EMPTY = new MiscData(false, new byte[0]);

    public byte[] encode() {
        ByteWriter writer = new ByteWriter(16 + cursorItem.length);
        writer.writeBoolean(hasCursorItem);
        if (hasCursorItem) {
            writer.writeLengthPrefixed(cursorItem);
        }
        return writer.toByteArray();
    }

    public static MiscData decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        boolean has = reader.readBoolean();
        byte[] item = has ? reader.readLengthPrefixed(Protocol.MAX_SEGMENT_BYTES) : new byte[0];
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in misc data");
        }
        return new MiscData(has, item);
    }
}
