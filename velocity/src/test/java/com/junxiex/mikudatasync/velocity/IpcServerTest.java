package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.Frame;
import com.junxiex.mikudatasync.common.FrameCodec;
import com.junxiex.mikudatasync.common.HmacAuth;
import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Opcode;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpcServerTest {

    private static final String SECRET = "test-secret-0123456789";
    private static final String SERVER_ID = "s1";

    @TempDir
    Path tempDir;

    private JournalStore journal;
    private SnapshotStore store;
    private IpcServer server;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    @BeforeEach
    void setUp() throws Exception {
        VelocityConfig config = new VelocityConfig("127.0.0.1", 0, SECRET, "data/journal.mds",
                VelocityConfig.FsyncMode.NEVER, 50, 2000, 2000, "closed", 30, 1 << 20, 60000, false, 20, "",
                64L << 20, 256L << 20, null);
        journal = new JournalStore(tempDir.resolve("journal.mds"), VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE,
                LoggerFactory.getLogger("test"));
        store = new SnapshotStore(1 << 20, journal);
        server = new IpcServer(LoggerFactory.getLogger("test"), config, store, Set.of(SERVER_ID));
        server.start();
        socket = new Socket("127.0.0.1", server.boundPort());
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
        handshake(SECRET);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (socket != null) {
            socket.close();
        }
        server.close();
        store.close();
    }

    private void handshake(String secret) throws Exception {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String message = SERVER_ID + "|" + timestamp + "|" + nonce + "|" + Protocol.PROTOCOL_VERSION;
        byte[] payload = new ByteWriter(256)
                .writeInt(Protocol.PROTOCOL_VERSION)
                .writeUtf(SERVER_ID)
                .writeLong(timestamp)
                .writeUtf(nonce)
                .writeUtf(HmacAuth.signHex(secret, message))
                .toByteArray();
        FrameCodec.write(out, new Frame(Opcode.HELLO, 1L, payload));
        Frame ack = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        assertEquals(Opcode.HELLO_ACK, ack.opcode());
    }

    private static SnapshotMessage message(byte value) throws Exception {
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 5023,
                Map.of(Segment.CORE, new byte[] {value}));
        return new SnapshotMessage(0L, 1L, "switch", SERVER_ID, envelope.encode());
    }

    @Test
    void putGetAndAckFlow() throws Exception {
        UUID uuid = UUID.randomUUID();
        FrameCodec.write(out, new Frame(Opcode.PUT, 10L, IpcPayloads.putRequest(uuid, message((byte) 7))));
        Frame ok = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        assertEquals(Opcode.PUT_OK, ok.opcode());
        assertEquals(1L, IpcPayloads.readPutOk(ok.payload()));

        FrameCodec.write(out, new Frame(Opcode.GET, 11L, IpcPayloads.uuid(uuid)));
        Frame snapshot = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        assertEquals(Opcode.SNAPSHOT, snapshot.opcode());
        SnapshotMessage read = IpcPayloads.readSnapshotResponse(snapshot.payload());
        assertNotNull(read);
        assertEquals(1L, read.seq());

        FrameCodec.write(out, new Frame(Opcode.ACK, 12L, IpcPayloads.ack(uuid, 1L)));
        Frame pong = new Frame(Opcode.PING, 13L, new byte[0]);
        FrameCodec.write(out, pong);
        assertEquals(Opcode.PONG, FrameCodec.read(in, Protocol.MAX_FRAME_BYTES).opcode());
        assertEquals(1L, store.get(uuid).appliedSeq());
    }

    @Test
    void flushRequestCompletesOnPut() throws Exception {
        UUID uuid = UUID.randomUUID();
        IpcServer.Handoff handoff = server.startHandoff(SERVER_ID, uuid, 2000L);
        Frame flush = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        assertEquals(Opcode.FLUSH_REQ, flush.opcode());
        assertEquals(handoff.gateId(), flush.requestId());
        // The backend allocates its own request id for the handoff PUT and echoes the gate id as the
        // token inside the payload instead of reusing the proxy's request id.
        long putRequestId = handoff.gateId() + 1000L;
        FrameCodec.write(out, new Frame(Opcode.PUT, putRequestId,
                IpcPayloads.putRequest(uuid, message((byte) 3), handoff.gateId())));
        Frame ok = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        assertEquals(Opcode.PUT_OK, ok.opcode());
        assertEquals(putRequestId, ok.requestId());
        Frame completed = handoff.future().get(2, TimeUnit.SECONDS);
        assertEquals(Opcode.PUT, completed.opcode());
        assertEquals(1L, store.get(uuid).seq());
    }

    @Test
    void errorFrameFailsPendingRequest() throws Exception {
        UUID uuid = UUID.randomUUID();
        IpcServer.Handoff handoff = server.startHandoff(SERVER_ID, uuid, 5000L);
        Frame flush = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        FrameCodec.write(out, new Frame(Opcode.ERROR, flush.requestId(),
                new ByteWriter(32).writeUtf("capture failed").toByteArray()));
        var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> handoff.future().get(2, TimeUnit.SECONDS));
        assertTrue(thrown.getCause().getMessage().contains("backend error"));
    }

    @Test
    void getWaitsForInFlightFlush() throws Exception {
        UUID uuid = UUID.randomUUID();
        IpcServer.Handoff handoff = server.startHandoff(SERVER_ID, uuid, 3000L);
        FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        FrameCodec.write(out, new Frame(Opcode.GET, 77L, IpcPayloads.uuid(uuid)));
        Thread.sleep(150);
        FrameCodec.write(out, new Frame(Opcode.PUT, 4242L,
                IpcPayloads.putRequest(uuid, message((byte) 5), handoff.gateId())));
        // The gate completion (which releases the waiting GET) and the PUT_OK are written by different
        // threads, so only their presence is guaranteed, not their order.
        java.util.Set<Opcode> opcodes = new java.util.HashSet<>();
        for (int i = 0; i < 2; i++) {
            Frame frame = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
            opcodes.add(frame.opcode());
            if (frame.opcode() == Opcode.PUT_OK) {
                assertEquals(4242L, frame.requestId());
            } else if (frame.opcode() == Opcode.SNAPSHOT) {
                assertNotNull(IpcPayloads.readSnapshotResponse(frame.payload()));
            }
        }
        assertEquals(java.util.Set.of(Opcode.PUT_OK, Opcode.SNAPSHOT), opcodes);
        handoff.future().get(2, TimeUnit.SECONDS);
    }

    @Test
    void latePutFromAnOlderHandoffDoesNotReleaseTheCurrentGate() throws Exception {
        UUID uuid = UUID.randomUUID();
        IpcServer.Handoff first = server.startHandoff(SERVER_ID, uuid, 300L);
        FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> first.future().get(2, TimeUnit.SECONDS));

        IpcServer.Handoff second = server.startHandoff(SERVER_ID, uuid, 3000L);
        FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);

        // The PUT that answers the first handoff arrives while the second gate is waiting.
        FrameCodec.write(out, new Frame(Opcode.PUT, 5001L,
                IpcPayloads.putRequest(uuid, message((byte) 6), first.gateId())));
        assertEquals(Opcode.PUT_OK, FrameCodec.read(in, Protocol.MAX_FRAME_BYTES).opcode());
        assertFalse(second.future().isDone(), "a stale handoff PUT must not release the current gate");

        FrameCodec.write(out, new Frame(Opcode.PUT, 5002L,
                IpcPayloads.putRequest(uuid, message((byte) 7), second.gateId())));
        assertEquals(Opcode.PUT_OK, FrameCodec.read(in, Protocol.MAX_FRAME_BYTES).opcode());
        second.future().get(2, TimeUnit.SECONDS);
    }

    @Test
    void latePutIsAttributedToTheHandoffThatTimedOut() throws Exception {
        UUID uuid = UUID.randomUUID();
        IpcServer.Handoff handoff = server.startHandoff(SERVER_ID, uuid, 300L);
        FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> handoff.future().get(2, TimeUnit.SECONDS));

        FrameCodec.write(out, new Frame(Opcode.PUT, 6001L,
                IpcPayloads.putRequest(uuid, message((byte) 9), handoff.gateId())));
        assertEquals(Opcode.PUT_OK, FrameCodec.read(in, Protocol.MAX_FRAME_BYTES).opcode());
        assertTrue(server.wasCommitted(uuid, handoff.gateId()));
        assertFalse(server.wasCommitted(uuid, handoff.gateId()), "the commit mark is consumed by one abort");
    }

    @Test
    void aPutAnsweredAsNotStoredIsDiscardedInsteadOfCommitted() throws Exception {
        UUID uuid = UUID.randomUUID();
        IpcServer.Handoff handoff = server.startHandoff(SERVER_ID, uuid, 300L);
        FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> handoff.future().get(2, TimeUnit.SECONDS));

        // The abort answered "not stored"; the PUT that was still in flight must not commit, otherwise
        // the backend would hold the cursor item twice (once handed back, once in the snapshot).
        assertFalse(server.wasCommitted(uuid, handoff.gateId()));
        FrameCodec.write(out, new Frame(Opcode.PUT, 7001L,
                IpcPayloads.putRequest(uuid, message((byte) 9), handoff.gateId())));
        assertEquals(Opcode.NACK, FrameCodec.read(in, Protocol.MAX_FRAME_BYTES).opcode());
        assertNull(store.get(uuid));
    }

    @Test
    void badHandshakeIsRejected() throws Exception {
        try (Socket bad = new Socket("127.0.0.1", server.boundPort())) {
            DataInputStream badIn = new DataInputStream(bad.getInputStream());
            DataOutputStream badOut = new DataOutputStream(bad.getOutputStream());
            long timestamp = System.currentTimeMillis();
            String message = "s1|" + timestamp + "|nonce|" + Protocol.PROTOCOL_VERSION;
            byte[] payload = new ByteWriter(256)
                    .writeInt(Protocol.PROTOCOL_VERSION)
                    .writeUtf("s1")
                    .writeLong(timestamp)
                    .writeUtf("nonce")
                    .writeUtf(HmacAuth.signHex("wrong-secret-012345", message))
                    .toByteArray();
            FrameCodec.write(badOut, new Frame(Opcode.HELLO, 1L, payload));
            assertEquals(Opcode.ERROR, FrameCodec.read(badIn, Protocol.MAX_FRAME_BYTES).opcode());
        }
    }
}