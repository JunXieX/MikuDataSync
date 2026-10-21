package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalStoreTest {

    @TempDir
    Path tempDir;

    private static SnapshotMessage message(long seq, int value) {
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 5023,
                Map.of(Segment.CORE, new byte[] {(byte) value}));
        return new SnapshotMessage(seq, seq, "switch", "server-1", envelope);
    }

    @Test
    void appendAndReplay() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore store = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            store.append(uuid, message(1, 1), true);
            store.append(uuid, message(2, 2), false);
            assertNotNull(store.get(uuid));
            assertEquals(2L, store.get(uuid).seq());
        }
        try (JournalStore store = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            assertEquals(2L, store.get(uuid).seq());
            assertEquals(1, store.trackedPlayers());
        }
    }

    @Test
    void corruptTailIsTruncated() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore store = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            store.append(uuid, message(1, 1), true);
        }
        try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.APPEND)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        }
        long before = Files.size(path);
        try (JournalStore store = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            assertEquals(1L, store.get(uuid).seq());
            assertEquals(1, store.trackedPlayers());
        }
        assertTrue(Files.size(path) < before);
    }

    @Test
    void compactionKeepsLatestSnapshot() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore store = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, 1L)) {
            for (int i = 1; i <= 20; i++) {
                store.append(uuid, message(i, i), false);
            }
            long before = store.size();
            store.maybeCompact();
            assertTrue(store.size() < before);
            assertEquals(20L, store.get(uuid).seq());
        }
        try (JournalStore store = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            assertEquals(20L, store.get(uuid).seq());
        }
    }

    @Test
    void missingUuidReturnsNull() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        try (JournalStore store = new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, Long.MAX_VALUE)) {
            assertNull(store.get(UUID.randomUUID()));
        }
    }
}
