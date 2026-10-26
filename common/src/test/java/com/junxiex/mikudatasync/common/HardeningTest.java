package com.junxiex.mikudatasync.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the 1.6.0 hardening pass. */
class HardeningTest {

    @Test
    void innerLimitIsConsistentBetweenEncodeAndDecode() throws Exception {
        // Nine incompressible segments of 512KB each: every segment is legal on its own, but the
        // combined inner body exceeds MAX_INNER_BYTES, so encode must refuse it.
        java.util.Random random = new java.util.Random(11L);
        java.util.Map<Integer, byte[]> segments = new java.util.HashMap<>();
        for (int id = 1; id <= 9; id++) {
            byte[] segment = new byte[Protocol.MAX_SEGMENT_BYTES];
            random.nextBytes(segment);
            segments.put(id, segment);
        }
        assertThrows(ProtocolException.class,
                () -> new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 1, segments).encode());
    }

    @Test
    void compressedLimitIsEnforcedSeparately() throws Exception {
        // Incompressible data within the inner budget but above the compressed budget must fail too.
        java.util.Random random = new java.util.Random(5L);
        byte[] segment = new byte[Protocol.MAX_SEGMENT_BYTES];
        random.nextBytes(segment);
        java.util.Map<Integer, byte[]> segments = new java.util.HashMap<>();
        segments.put(Segment.INVENTORY, segment);
        segments.put(Segment.PDC, segment.clone());
        segments.put(Segment.STATISTICS, segment.clone());
        assertThrows(ProtocolException.class,
                () -> new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 1, segments).encode());
    }

    @Test
    void snapshotMessageRejectsOverlongServerId() {
        byte[] envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 1, Map.of(Segment.CORE, new byte[] {1}))
                .encode();
        String tooLong = "s".repeat(Protocol.MAX_SERVER_ID_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotMessage(1L, 1L, "quit", tooLong, envelope).encode());
    }

    @Test
    void overlongVarIntIsRejected() {
        byte[] malformed = new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01};
        assertThrows(ProtocolException.class, () -> new ByteReader(malformed).readVarInt());
    }

    @Test
    void duplicateSegmentIdIsRejected() throws Exception {
        byte[] payload = new byte[] {7};
        ByteWriter inner = new ByteWriter(64);
        inner.writeByte(0);
        inner.writeInt(1);
        inner.writeShort(2);
        inner.writeShort(Segment.CORE);
        inner.writeInt(payload.length);
        inner.writeBytes(payload);
        inner.writeShort(Segment.CORE);
        inner.writeInt(payload.length);
        inner.writeBytes(payload);
        byte[] raw = inner.toByteArray();
        ByteWriter out = new ByteWriter(raw.length + 18);
        out.writeInt(Protocol.MAGIC);
        out.writeByte(Protocol.SCHEMA_VERSION);
        out.writeByte(0);
        out.writeInt(raw.length);
        out.writeBytes(raw);
        byte[] body = out.toByteArray();
        ByteWriter withCrc = new ByteWriter(body.length + 4);
        withCrc.writeBytes(body);
        withCrc.writeInt(Crc32c.compute(body, 0, body.length));
        assertThrows(ProtocolException.class, () -> SnapshotEnvelope.decode(withCrc.toByteArray()));
    }

    @Test
    void quotedScalarsKeepTheirType() throws Exception {
        Map<String, Object> root = MiniYaml.parse(String.join("\n",
                "store:",
                "  retain: \"true\"",
                "  budget: \"2097152\"",
                "  name: \"plain\"",
                ""));
        assertTrue(MiniYaml.getBoolean(root, "store.retain", false));
        assertEquals(2097152, MiniYaml.getInt(root, "store.budget", 0));
        assertEquals("plain", MiniYaml.getString(root, "store.name", ""));
        assertFalse(MiniYaml.getBoolean(root, "store.missing", false));
    }

    @Test
    void abortPayloadCarriesCommitFlag() throws Exception {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        IpcPayloads.AbortRequest committed = IpcPayloads.readAbort(IpcPayloads.abort(uuid, true));
        assertEquals(uuid, committed.uuid());
        assertTrue(committed.snapshotCommitted());
        IpcPayloads.AbortRequest aborted = IpcPayloads.readAbort(IpcPayloads.abort(uuid, false));
        assertFalse(aborted.snapshotCommitted());
        // A UUID-only payload is a protocol error now: both sides must speak the same version.
        assertThrows(ProtocolException.class, () -> IpcPayloads.readAbort(IpcPayloads.uuid(uuid)));
    }

    @Test
    void statisticsAndAdvancementListsRoundTripThroughPreparedShape() throws Exception {
        List<StatisticsList.Entry> stats = List.of(new StatisticsList.Entry("minecraft:custom", "a", 5));
        assertEquals(stats, StatisticsList.decode(StatisticsList.encode(stats)));
        byte[] colors = new byte[MapDataList.COLORS_LENGTH];
        java.util.Arrays.fill(colors, (byte) 4);
        assertArrayEquals(colors, MapDataList.decode(MapDataList.encode(
                List.of(new MapDataList.Entry(1, (byte) 1, 0, 0, true, colors)))).get(0).colors());
    }
}
