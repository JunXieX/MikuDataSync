package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the 1.6.0 journal/store hardening pass. */
class JournalHardeningTest {

    @TempDir
    Path tempDir;

    private static JournalStore newStore(Path path, long threshold) throws Exception {
        return new JournalStore(path, VelocityConfig.FsyncMode.NEVER, 10, threshold,
                LoggerFactory.getLogger("journal-hardening"));
    }

    private static SnapshotMessage message(String cause, byte value) throws Exception {
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 5023,
                Map.of(Segment.CORE, new byte[] {value}));
        return new SnapshotMessage(0L, 1L, cause, "server-1", envelope.encode());
    }

    @Test
    void seqStaysMonotonicAcrossEviction() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore journal = newStore(path, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(1 << 20, journal);
            assertEquals(1L, store.commit(uuid, message("quit", (byte) 1)));
            assertEquals(2L, store.commit(uuid, message("quit", (byte) 2)));
            store.evict(uuid);
            assertEquals(3L, store.commit(uuid, message("quit", (byte) 3)));
        }
    }

    @Test
    void seqStaysMonotonicAcrossRestart() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore journal = newStore(path, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(1 << 20, journal);
            assertEquals(1L, store.commit(uuid, message("quit", (byte) 1)));
        }
        try (JournalStore journal = newStore(path, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(1 << 20, journal);
            assertEquals(2L, store.commit(uuid, message("quit", (byte) 2)));
        }
    }

    @Test
    void compactionKeepsServingAndStaysReadable() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (JournalStore journal = newStore(path, 1L)) {
            for (int i = 0; i < 20; i++) {
                journal.append(uuid, message("quit", (byte) i), false);
            }
            journal.append(other, message("quit", (byte) 9), false);
            long before = journal.size();
            journal.maybeCompact();
            assertTrue(journal.size() < before, "compaction should shrink the journal");
            assertNotNull(journal.get(uuid));
            assertNotNull(journal.get(other));
        }
        try (JournalStore journal = newStore(path, Long.MAX_VALUE)) {
            assertNotNull(journal.get(uuid));
            assertNotNull(journal.get(other));
        }
    }

    @Test
    void recordsAppendedDuringCompactionSurvive() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        UUID uuid = UUID.randomUUID();
        try (JournalStore journal = newStore(path, 1L)) {
            for (int i = 0; i < 10; i++) {
                journal.append(uuid, message("quit", (byte) i), false);
            }
            Thread compactor = new Thread(journal::maybeCompact);
            compactor.start();
            for (int i = 0; i < 30; i++) {
                journal.append(uuid, message("periodic", (byte) (i + 100)), false);
            }
            compactor.join(5000L);
            SnapshotMessage latest = journal.get(uuid);
            assertNotNull(latest);
            // The last append during compaction must still be the visible record.
            assertEquals("periodic", latest.saveCause());
        }
        try (JournalStore journal = newStore(path, Long.MAX_VALUE)) {
            assertNotNull(journal.get(uuid));
        }
    }

    @Test
    void retainedBudgetEvictsOldestEntries() throws Exception {
        Path path = tempDir.resolve("journal.mds");
        try (JournalStore journal = newStore(path, Long.MAX_VALUE)) {
            SnapshotStore store = new SnapshotStore(1 << 20, journal, 4096L);
            for (int i = 0; i < 200; i++) {
                store.commit(UUID.randomUUID(), message("quit", (byte) i));
            }
            assertTrue(store.size() < 200, "byte budget should bound the retained table");
        }
    }
}
