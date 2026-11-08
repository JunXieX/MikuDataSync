package com.junxiex.mikudatasync.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Versioned binary container for one player snapshot.
 *
 * <pre>
 * magic:i32 | schema:u8 | compression:u8 | rawInnerLength:i32 | payload | crc32c:i32
 * inner: flags:u8 | dataVersion:i32 | segmentCount:u16 | [segmentId:u16, length:i32, bytes]*
 * </pre>
 * compression 0 = raw, 1 = GZIP(inner).
 */
public final class SnapshotEnvelope {

    /** Segment-count ceiling; encode and decode must agree or writeShort would truncate silently. */
    public static final int MAX_SEGMENTS = 64;

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

    public byte[] encode() throws ProtocolException {
        if (segments.size() > MAX_SEGMENTS) {
            throw new ProtocolException("too many segments: " + segments.size());
        }
        ByteWriter inner = new ByteWriter(256);
        inner.writeByte(flags());
        inner.writeInt(dataVersion);
        inner.writeShort(segments.size());
        for (Map.Entry<Integer, byte[]> entry : segments.entrySet()) {
            int id = entry.getKey();
            byte[] data = entry.getValue();
            if (id <= 0 || id > 0xFFFF) {
                throw new ProtocolException("invalid segment id: " + id);
            }
            if (data.length > Protocol.MAX_SEGMENT_BYTES) {
                throw new ProtocolException("segment " + id + " too large: " + data.length);
            }
            inner.writeShort(id);
            inner.writeInt(data.length);
            inner.writeBytes(data);
        }
        byte[] raw = inner.toByteArray();
        if (raw.length > Protocol.MAX_INNER_BYTES) {
            throw new ProtocolException("snapshot inner body too large: " + raw.length);
        }
        byte compression = 0;
        byte[] payload = raw;
        if (raw.length > 256) {
            byte[] gzip = gzip(raw);
            if (gzip.length + 8 < raw.length) {
                payload = gzip;
                compression = 1;
            }
        }
        if (payload.length + 14 > Protocol.MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("snapshot too large: " + (payload.length + 14));
        }

        ByteWriter out = new ByteWriter(payload.length + 14);
        out.writeInt(Protocol.MAGIC);
        out.writeByte(schemaVersion);
        out.writeByte(compression);
        out.writeInt(raw.length);
        out.writeBytes(payload);
        byte[] body = out.toByteArray();
        int crc = Crc32c.compute(body, 0, body.length);
        byte[] result = new byte[body.length + 4];
        System.arraycopy(body, 0, result, 0, body.length);
        result[body.length] = (byte) (crc >>> 24);
        result[body.length + 1] = (byte) (crc >>> 16);
        result[body.length + 2] = (byte) (crc >>> 8);
        result[body.length + 3] = (byte) crc;
        return result;
    }

    public static SnapshotEnvelope decode(byte[] bytes) throws ProtocolException {
        if (bytes.length < 14) {
            throw new ProtocolException("snapshot is too small: " + bytes.length);
        }
        ByteReader reader = new ByteReader(bytes);
        int magic = reader.readInt();
        if (magic != Protocol.MAGIC) {
            throw new ProtocolException("bad snapshot magic: 0x" + Integer.toHexString(magic));
        }
        int schemaVersion = reader.readByte();
        if (schemaVersion != Protocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported snapshot schema: " + schemaVersion);
        }
        int compression = reader.readByte();
        int rawLength = reader.readInt();
        if (rawLength < 0 || rawLength > Protocol.MAX_INNER_BYTES) {
            throw new ProtocolException("invalid inner length: " + rawLength);
        }
        byte[] payload = reader.readBytes(bytes.length - 14);
        int expectedCrc = reader.readInt();
        int actualCrc = Crc32c.compute(bytes, 0, bytes.length - 4);
        if (expectedCrc != actualCrc) {
            throw new ProtocolException("snapshot crc mismatch");
        }
        byte[] raw;
        if (compression == 1) {
            raw = gunzip(payload, rawLength);
        } else if (compression == 0) {
            raw = payload;
        } else {
            throw new ProtocolException("unknown snapshot compression: " + compression);
        }
        if (raw.length != rawLength) {
            throw new ProtocolException("inner length mismatch: " + raw.length + " != " + rawLength);
        }
        ByteReader inner = new ByteReader(raw);
        // Segment presence bitmap, derived from the table below and covered by the CRC. Read but not
        // compared: comparing it would make adding a segment id later invalidate every record that an
        // older build already wrote.
        inner.readByte();
        int dataVersion = inner.readInt();
        int count = inner.readUnsignedShort();
        if (count > MAX_SEGMENTS) {
            throw new ProtocolException("invalid segment count: " + count);
        }
        TreeMap<Integer, byte[]> segments = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int id = inner.readUnsignedShort();
            int length = inner.readInt();
            if (id <= 0) {
                throw new ProtocolException("invalid segment id: " + id);
            }
            if (length < 0 || length > Protocol.MAX_SEGMENT_BYTES) {
                throw new ProtocolException("invalid segment length: " + length);
            }
            if (segments.put(id, inner.readBytes(length)) != null) {
                throw new ProtocolException("duplicate segment id: " + id);
            }
        }
        if (inner.remaining() != 0) {
            throw new ProtocolException("unexpected bytes in snapshot body: " + inner.remaining());
        }
        return new SnapshotEnvelope(schemaVersion, dataVersion, segments);
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

    private static byte[] gzip(byte[] input) throws ProtocolException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(64, input.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(input);
        } catch (IOException e) {
            throw new ProtocolException("failed to compress snapshot: " + e.getMessage());
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] input, int expectedLength) throws ProtocolException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(64, expectedLength));
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input))) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = gzip.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (output.size() > Protocol.MAX_INNER_BYTES) {
                    throw new ProtocolException("decompressed snapshot exceeds limit");
                }
            }
        } catch (IOException e) {
            throw new ProtocolException("failed to decompress snapshot: " + e.getMessage());
        }
        return output.toByteArray();
    }
}
