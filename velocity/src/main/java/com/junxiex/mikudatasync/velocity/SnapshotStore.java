package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.SnapshotMessage;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private final int maxSnapshotBytes;
    private final JournalStore journal;

    public SnapshotStore(int maxSnapshotBytes, JournalStore journal) {
        this.maxSnapshotBytes = maxSnapshotBytes;
        this.journal = journal;
    }

    public Entry get(UUID uuid) {
        Entry entry = entries.get(uuid);
        if (entry != null) {
            return entry;
        }
        if (journal == null) {
            return null;
        }
        try {
            SnapshotMessage message = journal.get(uuid);
            if (message == null) {
                return null;
            }
            return new Entry(message.seq(), message.captureSeq(), 0L, message.saveCause(),
                    message.sourceServer(), message.envelope(), sha256Hex(message.envelope()), 0L);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized long commit(UUID uuid, SnapshotMessage message) throws IOException, ProtocolException {
        if (message.envelope().length > maxSnapshotBytes) {
            throw new ProtocolException("snapshot exceeds configured limit: " + message.envelope().length);
        }
        String hash = sha256Hex(message.envelope());
        Entry previous = entries.get(uuid);
        if (previous != null && previous.hash().equals(hash)) {
            return previous.seq();
        }
        long seq = previous == null ? 1L : previous.seq() + 1L;
        long appliedSeq = previous == null ? 0L : previous.appliedSeq();
        SnapshotMessage committed = new SnapshotMessage(seq, message.captureSeq(), message.saveCause(),
                message.sourceServer(), message.envelope());
        if (journal != null) {
            journal.append(uuid, committed, true);
        }
        entries.put(uuid, new Entry(seq, message.captureSeq(), appliedSeq, message.saveCause(),
                message.sourceServer(), message.envelope(), hash, System.currentTimeMillis()));
        return seq;
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
        entries.remove(uuid);
    }

    public int size() {
        return entries.size();
    }

    public String journalStatus() {
        if (journal == null) {
            return "disabled";
        }
        return "bytes=" + journal.size() + ", players=" + journal.trackedPlayers();
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
