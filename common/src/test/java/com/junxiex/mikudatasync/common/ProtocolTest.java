package com.junxiex.mikudatasync.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    @Test
    void envelopeRoundTrip() throws Exception {
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 4321, Map.of(
                Segment.INVENTORY, new byte[] {1, 2, 3, 4},
                Segment.PDC, new byte[] {9, 9}
        ));

        byte[] encoded = envelope.encode();
        SnapshotEnvelope decoded = SnapshotEnvelope.decode(encoded);

        assertEquals(Protocol.SCHEMA_VERSION, decoded.schemaVersion());
        assertEquals(4321, decoded.dataVersion());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, decoded.segment(Segment.INVENTORY));
        assertArrayEquals(new byte[] {9, 9}, decoded.segment(Segment.PDC));
        assertFalse(decoded.hasSegment(Segment.STATISTICS));
    }

    @Test
    void envelopeRejectsCorruptedBody() throws Exception {
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 1, Map.of(
                Segment.INVENTORY, new byte[] {1, 2, 3}
        ));
        byte[] encoded = envelope.encode();
        encoded[14] = (byte) (encoded[14] ^ 0x01);
        assertThrows(ProtocolException.class, () -> SnapshotEnvelope.decode(encoded));
    }

    @Test
    void frameRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame(Opcode.PUT, 42L, new byte[] {7, 8, 9}));

        Frame frame = FrameCodec.read(new ByteArrayInputStream(out.toByteArray()), Protocol.MAX_FRAME_BYTES);
        assertEquals(Opcode.PUT, frame.opcode());
        assertEquals(42L, frame.requestId());
        assertArrayEquals(new byte[] {7, 8, 9}, frame.payload());
    }

    @Test
    void miniYamlParsesNestedConfig() throws Exception {
        String yaml = """
                protocol:
                  bind: "127.0.0.1"
                  port: 25666
                  enabled: true
                  ratio: 0.5
                sync:
                  fail-mode: "closed"
                """;

        Map<String, Object> root = MiniYaml.parse(yaml);
        assertEquals("127.0.0.1", MiniYaml.getString(root, "protocol.bind", ""));
        assertEquals(25666, MiniYaml.getInt(root, "protocol.port", 0));
        assertTrue(MiniYaml.getBoolean(root, "protocol.enabled", false));
        assertEquals(0.5, (Double) MiniYaml.value(root, "protocol.ratio"), 0.0001);
        assertEquals("closed", MiniYaml.getString(root, "sync.fail-mode", "open"));
    }

    @Test
    void hmacRoundTrip() {
        String signature = HmacAuth.signHex("0123456789abcdef", "survival-1|123|nonce|1");
        assertTrue(HmacAuth.verifyHex("0123456789abcdef", "survival-1|123|nonce|1", signature));
        assertFalse(HmacAuth.verifyHex("0123456789abcdef", "tampered", signature));
    }

    @Test
    void varIntRoundTrip() throws Exception {
        int[] values = {0, 1, 127, 128, 300, 65535, Integer.MAX_VALUE};
        for (int value : values) {
            ByteWriter writer = new ByteWriter();
            writer.writeVarInt(value);
            assertEquals(value, new ByteReader(writer.toByteArray()).readVarInt());
        }
    }
}
