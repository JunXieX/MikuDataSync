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
        if (schemaVersion == 1) {
            // Read-only migration path for 1.1.8 and earlier journals.
            return decodeLegacy(bytes);
        }
        if (schemaVersion != Protocol.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported snapshot schema: " + schemaVersion);
        }
        int compression = reader.readByte();
        int rawLength = reader.readInt();
        if (rawLength < 0 || rawLength > Protocol.MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("invalid inner length: " + rawLength);
        }
        int payloadLength = bytes.length - 18;
        if (payloadLength < 0) {
            throw new ProtocolException("snapshot payload is too small");
        }
        byte[] payload = reader.readBytes(payloadLength);
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
        int flags = inner.readByte();
        int dataVersion = inner.readInt();
        int count = inner.readUnsignedShort();
        if (count > 64) {
            throw new ProtocolException("invalid segment count: " + count);
        }
        TreeMap<Integer, byte[]> segments = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int id = inner.readUnsignedShort();
            int length = inner.readInt();
            if (length < 0 || length > Protocol.MAX_SEGMENT_BYTES) {
                throw new ProtocolException("invalid segment length: " + length);
            }
            segments.put(id, inner.readBytes(length));
        }
        if (inner.remaining() != 0) {
            throw new ProtocolException("unexpected bytes in snapshot body: " + inner.remaining());
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

    private static SnapshotEnvelope decodeLegacy(byte[] bytes) throws ProtocolException {
        ByteReader reader = new ByteReader(bytes);
        reader.readInt();
        reader.readByte();
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
        SnapshotEnvelope envelope = new SnapshotEnvelope(1, dataVersion, segments);
        if (envelope.flags() != flags) {
            throw new ProtocolException("snapshot flags mismatch");
        }
        return envelope;
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
                if (output.size() > Protocol.MAX_SNAPSHOT_BYTES) {
                    throw new ProtocolException("decompressed snapshot exceeds limit");
                }
            }
        } catch (IOException e) {
            throw new ProtocolException("failed to decompress snapshot: " + e.getMessage());
        }
        return output.toByteArray();
    }
}
