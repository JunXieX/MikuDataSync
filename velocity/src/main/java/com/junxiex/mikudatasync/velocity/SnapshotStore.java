package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.SnapshotMessage;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative in-memory snapshot table backed by the append-only journal. A commit returns
 * only after the journal write (and fsync for switch/quit) has completed.
 */
public final class SnapshotStore {

    public record Entry(
            long seq,
            long captureSeq,
            long appliedSeq,
            String saveCause,
            String sourceServer,
            byte[] envelope,
            String hash,
            long updatedAt
    ) {
    }

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong retainedBytes = new java.util.concurrent.atomic.AtomicLong();
    private final int maxSnapshotBytes;
    private final long maxRetainedBytes;
    private final JournalStore journal;

    public SnapshotStore(int maxSnapshotBytes, JournalStore journal) {
        this(maxSnapshotBytes, journal, 0L);
    }

    public SnapshotStore(int maxSnapshotBytes, JournalStore journal, long maxRetainedBytes) {
        this.maxSnapshotBytes = maxSnapshotBytes;
        this.journal = journal;
        this.maxRetainedBytes = maxRetainedBytes;
    }

    /**
     * Returns the snapshot for this player. A record that exists on disk but cannot be read raises
     * instead of looking like "no snapshot": silently treating a corrupt record as missing let a
     * player log in with stale data and produced no log line at all.
     */
    public Entry get(UUID uuid) throws IOException {
        Entry entry = entries.get(uuid);
        if (entry != null) {
            return entry;
        }
        if (journal == null) {
            return null;
        }
        SnapshotMessage message = journal.get(uuid);
        if (message == null) {
            return null;
        }
        // Deliberately not cached: with retain-offline-snapshots disabled this table must not grow
        // with every pre-login read, and the journal is the authoritative copy anyway.
        return new Entry(message.seq(), message.captureSeq(), 0L, message.saveCause(),
                message.sourceServer(), message.envelope(), sha256Hex(message.envelope()), 0L);
    }

    public synchronized long commit(UUID uuid, SnapshotMessage message) throws IOException, ProtocolException {
        if (message.envelope().length > maxSnapshotBytes) {
            throw new ProtocolException("snapshot exceeds configured limit: " + message.envelope().length);
        }
        String hash = sha256Hex(message.envelope());
        Entry previous = entries.get(uuid);
        if (previous == null && journal != null) {
            // Sequence numbers must stay monotonic across eviction and restarts; otherwise a player
            // that was evicted while offline comes back with seq=1 and dedup stops working.
            long persistedSeq = journal.latestSeq(uuid);
            if (persistedSeq > 0L) {
                // appliedSeq is not persisted; keep the estimate monotonic with the stored sequence so
                // ACK bookkeeping cannot regress after a restart.
                previous = new Entry(persistedSeq, message.captureSeq(), persistedSeq - 1L, message.saveCause(),
                        message.sourceServer(), new byte[0], "", 0L);
            }
        }
        if (previous != null && !previous.hash().isEmpty() && previous.hash().equals(hash)) {
            if (journal != null && !"periodic".equalsIgnoreCase(message.saveCause())) {
                journal.forceIfDirty();
            }
            return previous.seq();
        }
        long seq = previous == null ? 1L : previous.seq() + 1L;
        long appliedSeq = previous == null ? 0L : previous.appliedSeq();
        SnapshotMessage committed = new SnapshotMessage(seq, message.captureSeq(), message.saveCause(),
                message.sourceServer(), message.envelope());
        if (journal != null) {
            boolean force = !"periodic".equalsIgnoreCase(message.saveCause())
                    && !"selftest".equalsIgnoreCase(message.saveCause());
            journal.append(uuid, committed, force);
        }
        Entry stored = new Entry(seq, message.captureSeq(), appliedSeq, message.saveCause(),
                message.sourceServer(), message.envelope(), hash, System.currentTimeMillis());
        Entry replaced = entries.put(uuid, stored);
        retainedBytes.addAndGet(stored.envelope().length - (replaced == null ? 0 : replaced.envelope().length));
        enforceBudget();
        return seq;
    }

    /**
     * Caps the retained snapshot table. Only entries already durable in the journal are dropped, so
     * eviction costs a disk read on the next access instead of losing data.
     */
    private void enforceBudget() {
        if (maxRetainedBytes <= 0L || entries.size() < 32) {
            return;
        }
        long total = retainedBytes.get();
        if (total <= maxRetainedBytes) {
            return;
        }
        // Evict down to a low-water mark. Stopping at the budget itself would put every following
        // commit back over it, so the whole table would be copied and sorted on each commit.
        long target = maxRetainedBytes - (maxRetainedBytes / 4L);
        List<java.util.Map.Entry<UUID, Entry>> byAge = new ArrayList<>(entries.size());
        entries.forEach((uuid, entry) -> byAge.add(java.util.Map.entry(uuid, entry)));
        byAge.sort(java.util.Comparator.comparingLong(entry -> entry.getValue().updatedAt()));
        for (java.util.Map.Entry<UUID, Entry> candidate : byAge) {
            if (total <= target) {
                break;
            }
            if (!entries.remove(candidate.getKey(), candidate.getValue())) {
                continue;
            }
            int length = candidate.getValue().envelope().length;
            retainedBytes.addAndGet(-length);
            total -= length;
        }
    }

    public void ack(UUID uuid, long appliedSeq) {
        entries.computeIfPresent(uuid, (id, entry) -> new Entry(
                entry.seq(),
                entry.captureSeq(),
                Math.max(entry.appliedSeq(), appliedSeq),
                entry.saveCause(),
                entry.sourceServer(),
                entry.envelope(),
                entry.hash(),
                entry.updatedAt()
        ));
    }

    public void evict(UUID uuid) {
        Entry removed = entries.remove(uuid);
        if (removed != null) {
            retainedBytes.addAndGet(-removed.envelope().length);
        }
    }

    public int size() {
        return entries.size();
    }

    public String journalStatus() {
        if (journal == null) {
            return "disabled";
        }
        String state = journal.isBroken() ? ", degraded=true" : "";
        return "bytes=" + journal.size() + ", players=" + journal.trackedPlayers() + state;
    }

    public void forceJournal() {
        if (journal != null) {
            journal.forceIfDirty();
        }
    }

    public void maybeCompact() {
        if (journal != null) {
            journal.maybeCompact();
        }
    }

    public void close() {
        if (journal != null) {
            journal.close();
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
