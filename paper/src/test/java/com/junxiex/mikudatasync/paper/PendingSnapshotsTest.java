package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the classes that do not need a running server. */
class PendingSnapshotsTest {

    private static PendingSnapshots.Pending pending(long createdAt) throws Exception {
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 1,
                Map.of(Segment.CORE, new byte[] {1}));
        return new PendingSnapshots.Pending(envelope, 1L, createdAt, null);
    }

    @Test
    void expiredEntriesAreDroppedOnWrite() throws Exception {
        PendingSnapshots snapshots = new PendingSnapshots();
        UUID stale = UUID.randomUUID();
        assertTrue(snapshots.put(stale, pending(System.currentTimeMillis() - 120_000L)));
        assertTrue(snapshots.put(UUID.randomUUID(), pending(System.currentTimeMillis())));
        assertNull(snapshots.remove(stale));
        assertEquals(1, snapshots.size());
    }

    @Test
    void removeOfExpiredEntryReturnsNull() throws Exception {
        PendingSnapshots snapshots = new PendingSnapshots();
        UUID uuid = UUID.randomUUID();
        assertTrue(snapshots.put(uuid, pending(System.currentTimeMillis() - 120_000L)));
        assertNull(snapshots.remove(uuid));
    }

    @Test
    void fullTableRefusesNewEntriesInsteadOfEvictingWaitingPlayers() throws Exception {
        PendingSnapshots snapshots = new PendingSnapshots();
        for (int i = 0; i < 256; i++) {
            assertTrue(snapshots.put(UUID.randomUUID(), pending(System.currentTimeMillis())));
        }
        UUID newcomer = UUID.randomUUID();
        assertFalse(snapshots.put(newcomer, pending(System.currentTimeMillis())));
        assertEquals(256, snapshots.size());
        assertNull(snapshots.remove(newcomer));
    }

    @Test
    void mapBindingsPersistOnlyChangedValues(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("map-bindings.properties");
        MapBindings bindings = new MapBindings(file, Logger.getLogger("test"));
        bindings.put("survival-1", 7, 100);
        assertFalse(Files.exists(file), "bindings are flushed in batches, not on every put");
        bindings.flush();
        assertTrue(Files.exists(file));

        MapBindings reloaded = new MapBindings(file, Logger.getLogger("test"));
        assertEquals(100, reloaded.get("survival-1", 7));

        // Re-binding the same key must also be persisted, otherwise a restart restores a stale id.
        bindings.put("survival-1", 7, 200);
        bindings.flush();
        assertEquals(200, new MapBindings(file, Logger.getLogger("test")).get("survival-1", 7));
    }
}
