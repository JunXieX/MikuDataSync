package com.junxiex.mikudatasync.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecCoverageTest {

    private static SnapshotMessage message(long seq, SnapshotEnvelope envelope) throws Exception {
        return new SnapshotMessage(seq, seq + 10, "switch", "server-1", envelope.encode());
    }

    @Test
    void envelopeRoundTripWithAllSegmentIds() throws Exception {
        Map<Integer, byte[]> segments = new java.util.HashMap<>();
        segments.put(Segment.CORE, new byte[] {1, 2, 3});
        segments.put(Segment.INVENTORY, new byte[] {4, 5});
        segments.put(Segment.ENDER_CHEST, new byte[] {6});
        segments.put(Segment.EFFECTS, new byte[] {7});
        segments.put(Segment.ATTRIBUTES, new byte[] {8});
        segments.put(Segment.ADVANCEMENTS, new byte[] {9});
        segments.put(Segment.STATISTICS, new byte[] {10});
        segments.put(Segment.PDC, new byte[] {11});
        segments.put(Segment.RECIPES, new byte[] {12});
        segments.put(Segment.LOCATION, new byte[] {13});
        segments.put(Segment.MISC, new byte[] {14});
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 4321, segments);
        SnapshotEnvelope decoded = SnapshotEnvelope.decode(envelope.encode());
        assertEquals(4321, decoded.dataVersion());
        assertEquals(11, decoded.segments().size());
        assertEquals(3, decoded.segment(Segment.CORE).length);
        assertTrue(decoded.hasSegment(Segment.MISC));
    }

    @Test
    void envelopeRejectsCorruption() throws Exception {
        SnapshotEnvelope envelope = new SnapshotEnvelope(1, 1, Map.of(Segment.CORE, new byte[] {1, 2, 3}));
        byte[] bytes = envelope.encode();
        bytes[16] ^= 0x7F;
        assertThrows(ProtocolException.class, () -> SnapshotEnvelope.decode(bytes));
    }

    @Test
    void envelopeRejectsBadMagicAndShort() throws Exception {
        assertThrows(ProtocolException.class, () -> SnapshotEnvelope.decode(new byte[3]));
        byte[] bytes = new SnapshotEnvelope(1, 1, Map.of(Segment.CORE, new byte[] {1})).encode();
        bytes[0] = 0;
        assertThrows(ProtocolException.class, () -> SnapshotEnvelope.decode(bytes));
    }

    @Test
    void envelopeRejectsOversizedSegmentAndInvalidId() {
        byte[] big = new byte[Protocol.MAX_SEGMENT_BYTES + 1];
        assertThrows(ProtocolException.class,
                () -> new SnapshotEnvelope(1, 1, Map.of(Segment.CORE, big)).encode());
        assertThrows(ProtocolException.class,
                () -> new SnapshotEnvelope(1, 1, Map.of(0, new byte[] {1})).encode());
    }

    @Test
    void ipcPayloadsRoundTrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertEquals(uuid, IpcPayloads.parseUuid(IpcPayloads.uuid(uuid)));

        UUID[] out = new UUID[1];
        long seq = IpcPayloads.readAck(IpcPayloads.ack(uuid, 42L), out);
        assertEquals(42L, seq);
        assertEquals(uuid, out[0]);
        assertEquals(77L, IpcPayloads.readPutOk(IpcPayloads.putOk(77L)));

        SnapshotEnvelope envelope = new SnapshotEnvelope(1, 99, Map.of(Segment.CORE, new byte[] {1}));
        SnapshotMessage msg = message(3, envelope);
        byte[] found = IpcPayloads.snapshotFound(msg);
        SnapshotMessage decoded = IpcPayloads.readSnapshotResponse(found);
        assertEquals(3L, decoded.seq());
        assertNull(IpcPayloads.readSnapshotResponse(IpcPayloads.snapshotMissing()));

        UUID[] putUuid = new UUID[1];
        SnapshotMessage put = IpcPayloads.readPutRequest(IpcPayloads.putRequest(uuid, msg), putUuid);
        assertEquals(uuid, putUuid[0]);
        assertEquals(msg.seq(), put.seq());
    }

    @Test
    void effectAttributeMiscAndNackRoundTrip() throws Exception {
        List<EffectList.Entry> effects = List.of(
                new EffectList.Entry("minecraft:speed", 600, 2, true, false, true),
                new EffectList.Entry("minecraft:regeneration", 100, 0, false, true, false));
        assertEquals(effects, EffectList.decode(EffectList.encode(effects)));

        List<AttributeList.Entry> attributes = List.of(
                new AttributeList.Entry("minecraft:generic.max_health", 20.0),
                new AttributeList.Entry("minecraft:generic.movement_speed", 0.1));
        assertEquals(attributes, AttributeList.decode(AttributeList.encode(attributes)));

        MiscData misc = new MiscData(true, new byte[] {9, 8, 7});
        MiscData decodedMisc = MiscData.decode(misc.encode());
        assertTrue(decodedMisc.hasCursorItem());
        assertArrayEquals(misc.cursorItem(), decodedMisc.cursorItem());
        MiscData emptyMisc = MiscData.decode(MiscData.EMPTY.encode());
        assertFalse(emptyMisc.hasCursorItem());
        assertEquals(0, emptyMisc.cursorItem().length);

        UUID uuid = UUID.randomUUID();
        NackMessage nack = new NackMessage(uuid, 12L, "apply_failed", "boom");
        assertEquals(nack, NackMessage.decode(nack.encode()));
    }

    @Test
    void miniYamlNestedAndTypedValues() throws Exception {
        Map<String, Object> root = MiniYaml.parse(String.join("\n",
                "protocol:",
                "  bind: 127.0.0.1",
                "  port: 25666",
                "  secret: abcdefghijklmnop",
                "store:",
                "  fsync-mode: interval",
                "  retain: false",
                "list: []",
                "map: {}",
                ""));
        assertEquals("127.0.0.1", MiniYaml.getString(root, "protocol.bind", "x"));
        assertEquals(25666, MiniYaml.getInt(root, "protocol.port", 1));
        assertFalse(MiniYaml.getBoolean(root, "store.retain", true));
        assertEquals("interval", MiniYaml.getString(root, "store.fsync-mode", "x"));
    }

    @Test
    void itemMapDataWithCursorsRoundTrip() throws Exception {
        byte[] colors = new byte[MapDataList.COLORS_LENGTH];
        java.util.Arrays.fill(colors, (byte) 3);
        var cursors = List.of(new ItemMapData.CursorData((byte) 10, (byte) 20, (byte) 1,
                "BANNER_RED", "home"));
        ItemMapData data = new ItemMapData("survival-1", 7, (byte) 2, 100, -200, true, colors, cursors);
        ItemMapData decoded = ItemMapData.decode(data.encode());
        assertEquals("survival-1", decoded.originServer());
        assertEquals(7, decoded.sourceMapId());
        assertEquals(1, decoded.cursors().size());
        assertEquals("BANNER_RED", decoded.cursors().get(0).type());
        assertArrayEquals(colors, decoded.colors());
    }

    @Test
    void mapDataRoundTrip() throws Exception {
        byte[] colors = new byte[MapDataList.COLORS_LENGTH];
        java.util.Arrays.fill(colors, (byte) 7);
        MapDataList.Entry entry = new MapDataList.Entry(42, (byte) 2, 100, -200, true, colors);
        List<MapDataList.Entry> decoded = MapDataList.decode(MapDataList.encode(List.of(entry)));
        assertEquals(1, decoded.size());
        assertEquals(42, decoded.get(0).mapId());
        assertEquals((byte) 2, decoded.get(0).scale());
        assertEquals(100, decoded.get(0).centerX());
        assertEquals(-200, decoded.get(0).centerZ());
        assertTrue(decoded.get(0).locked());
        assertArrayEquals(colors, decoded.get(0).colors());
    }

    @Test
    void compressionRoundTrip() throws Exception {
        java.util.Random random = new java.util.Random(7L);
        byte[] big = new byte[8192];
        random.nextBytes(big);
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 5023,
                Map.of(Segment.INVENTORY, big, Segment.CORE, new byte[] {1, 2, 3}));
        SnapshotEnvelope decoded = SnapshotEnvelope.decode(envelope.encode());
        assertArrayEquals(big, decoded.segment(Segment.INVENTORY));
    }

    @Test
    void legacySchemaStillDecodes() throws Exception {
        byte[] payload = new byte[] {1, 2, 3};
        ByteWriter writer = new ByteWriter(64);
        writer.writeInt(Protocol.MAGIC);
        writer.writeByte(1);
        writer.writeByte(1);
        writer.writeInt(5023);
        writer.writeShort(1);
        writer.writeShort(Segment.CORE);
        writer.writeInt(payload.length);
        writer.writeBytes(payload);
        byte[] body = writer.toByteArray();
        int crc = Crc32c.compute(body, 0, body.length);
        ByteWriter out = new ByteWriter(body.length + 4);
        out.writeBytes(body);
        out.writeInt(crc);
        SnapshotEnvelope decoded = SnapshotEnvelope.decode(out.toByteArray());
        assertEquals(1, decoded.schemaVersion());
        assertArrayEquals(payload, decoded.segment(Segment.CORE));
    }

    @Test
    void hmacVerification() {
        String secret = "test-secret-0123456789";
        String payload = "server-1|123|nonce|1";
        String hex = HmacAuth.signHex(secret, payload);
        assertTrue(HmacAuth.verifyHex(secret, payload, hex));
        assertFalse(HmacAuth.verifyHex(secret, payload + "x", hex));
        assertFalse(HmacAuth.verifyHex(secret, payload, "zz-not-hex"));
    }
}
