package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotStoreTest {

    @TempDir
    Path tempDir;

    private static SnapshotMessage message(long captureSeq, String cause, byte value) throws Exception {
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 5023,
                Map.of(Segment.CORE, new byte[] {value}));
        return new SnapshotMessage(0L, captureSeq, cause, "server-1", envelope.encode());
    }

    @Test
    void commitAssignsMonotonicSeqAndDeduplicates() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore journal = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(1 << 20, journal);
            assertEquals(1L, store.commit(uuid, message(1, "switch", (byte) 1)));
            assertEquals(2L, store.commit(uuid, message(2, "switch", (byte) 2)));
            assertEquals(2L, store.commit(uuid, message(3, "switch", (byte) 2)));
            assertEquals(2L, store.get(uuid).seq());
        }
    }

    @Test
    void ackIsMonotonicAndEvictRemovesMemoryEntry() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore journal = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(1 << 20, journal);
            store.commit(uuid, message(1, "quit", (byte) 1));
            store.ack(uuid, 5L);
            assertEquals(5L, store.get(uuid).appliedSeq());
            store.ack(uuid, 3L);
            assertEquals(5L, store.get(uuid).appliedSeq());
            store.evict(uuid);
            assertNotNull(store.get(uuid));
        }
    }

    @Test
    void journalFallbackAfterRestart() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore journal = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(1 << 20, journal);
            store.commit(uuid, message(1, "quit", (byte) 9));
        }
        try (JournalStore journal = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(1 << 20, journal);
            assertNotNull(store.get(uuid));
            assertEquals(1L, store.get(uuid).seq());
        }
    }

    @Test
    void maxSnapshotBytesIsEnforced() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore journal = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(8, journal);
            assertThrows(ProtocolException.class, () -> store.commit(uuid, message(1, "quit", (byte) 1)));
        }
    }

    @Test
    void noJournalStillWorks() throws Exception {
        SnapshotStore store = new SnapshotStore(1 << 20, null);
        UUID uuid = UUID.randomUUID();
        assertEquals(1L, store.commit(uuid, message(1, "quit", (byte) 1)));
        assertEquals("disabled", store.journalStatus());
        store.ack(uuid, 1L);
        store.evict(uuid);
        assertNull(store.get(uuid));
        store.close();
    }
}
