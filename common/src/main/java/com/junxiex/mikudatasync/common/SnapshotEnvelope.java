package com.junxiex.mikudatasync.common;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Versioned binary container for one player snapshot.
 *
 * <pre>
 * magic:int | schemaVersion:u8 | flags:u8 | dataVersion:int
 * segmentCount:u16 | [segmentId:u16, length:i32, bytes]*
 * crc32c:i32
 * </pre>
 */
public final class SnapshotEnvelope {

    private final int schemaVersion;
    private final int dataVersion;
    private final TreeMap<Integer, byte[]> segments;

    public SnapshotEnvelope(int schemaVersion, int dataVersion, Map<Integer, byte[]> segments) {
        this.schemaVersion = schemaVersion;
        this.dataVersion = dataVersion;
        this.segments = new TreeMap<>(segments);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public int dataVersion() {
        return dataVersion;
    }

    public Map<Integer, byte[]> segments() {
        return Collections.unmodifiableMap(segments);
    }

    public byte[] segment(int id) {
        return segments.get(id);
    }

    public boolean hasSegment(int id) {
        return segments.containsKey(id);
    }

    public SnapshotEnvelope withSegment(int id, byte[] data) {
        TreeMap<Integer, byte[]> copy = new TreeMap<>(segments);
        copy.put(id, data);
        return new SnapshotEnvelope(schemaVersion, dataVersion, copy);
    }

    public byte[] encode() throws ProtocolException {
        ByteWriter writer = new ByteWriter(256);
        writer.writeInt(Protocol.MAGIC);
        writer.writeByte(schemaVersion);
        writer.writeByte(flags());
        writer.writeInt(dataVersion);
        writer.writeShort(segments.size());
        for (Map.Entry<Integer, byte[]> entry : segments.entrySet()) {
            int id = entry.getKey();
            byte[] data = entry.getValue();
            if (id <= 0 || id > 0xFFFF) {
                throw new ProtocolException("invalid segment id: " + id);
            }
            if (data.length > Protocol.MAX_SEGMENT_BYTES) {
                throw new ProtocolException("segment " + id + " too large: " + data.length);
            }
            writer.writeShort(id);
            writer.writeInt(data.length);
            writer.writeBytes(data);
        }
        byte[] body = writer.toByteArray();
        if (body.length + 4 > Protocol.MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("snapshot too large: " + (body.length + 4));
        }
        int crc = Crc32c.compute(body, 0, body.length);
        byte[] out = new byte[body.length + 4];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = (byte) (crc >>> 24);
        out[body.length + 1] = (byte) (crc >>> 16);
        out[body.length + 2] = (byte) (crc >>> 8);
        out[body.length + 3] = (byte) crc;
        return out;
    }

    public static SnapshotEnvelope decode(byte[] bytes) throws ProtocolException {
        if (bytes.length < 16) {
            throw new ProtocolException("snapshot is too small: " + bytes.length);
        }
        ByteReader reader = new ByteReader(bytes);
        int magic = reader.readInt();
        if (magic != Protocol.MAGIC) {
            throw new ProtocolException("bad snapshot magic: 0x" + Integer.toHexString(magic));
        }
        int schemaVersion = reader.readByte();
        int flags = reader.readByte();
        int dataVersion = reader.readInt();
        int count = reader.readUnsignedShort();
        if (count > 64) {
            throw new ProtocolException("invalid segment count: " + count);
        }
        TreeMap<Integer, byte[]> segments = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int id = reader.readUnsignedShort();
            int length = reader.readInt();
            if (length < 0 || length > Protocol.MAX_SEGMENT_BYTES) {
                throw new ProtocolException("invalid segment length: " + length);
            }
            segments.put(id, reader.readBytes(length));
        }
        if (reader.remaining() != 4) {
            throw new ProtocolException("unexpected trailing bytes: " + reader.remaining());
        }
        int expectedCrc = reader.readInt();
        int actualCrc = Crc32c.compute(bytes, 0, bytes.length - 4);
        if (expectedCrc != actualCrc) {
            throw new ProtocolException("snapshot crc mismatch");
        }
        SnapshotEnvelope envelope = new SnapshotEnvelope(schemaVersion, dataVersion, segments);
        if (envelope.flags() != flags) {
            throw new ProtocolException("snapshot flags mismatch");
        }
        return envelope;
    }

    private int flags() {
        int flags = 0;
        for (int id : segments.keySet()) {
            if (id >= 1 && id <= 8) {
                flags |= 1 << (id - 1);
            }
        }
        return flags;
    }
}
