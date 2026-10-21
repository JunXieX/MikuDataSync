package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.SnapshotMessage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative in-memory snapshot table. Journal persistence is added in milestone M3; the
 * semantics here (monotonic seq + content hash dedupe + appliedSeq) already match the protocol.
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

    public SnapshotStore(int maxSnapshotBytes) {
        this.maxSnapshotBytes = maxSnapshotBytes;
    }

    public Entry get(UUID uuid) {
        return entries.get(uuid);
    }

    public long commit(UUID uuid, SnapshotMessage message) throws ProtocolException {
        if (message.envelope().length > maxSnapshotBytes) {
            throw new ProtocolException("snapshot exceeds configured limit: " + message.envelope().length);
        }
        String hash = sha256Hex(message.envelope());
        Entry result = entries.compute(uuid, (id, previous) -> {
            if (previous != null && previous.hash().equals(hash)) {
                return previous;
            }
            long seq = previous == null ? 1L : previous.seq() + 1L;
            long appliedSeq = previous == null ? 0L : previous.appliedSeq();
            return new Entry(seq, message.captureSeq(), appliedSeq, message.saveCause(), message.sourceServer(),
                    message.envelope(), hash, System.currentTimeMillis());
        });
        return result.seq();
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

    public int size() {
        return entries.size();
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
