package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.SnapshotEnvelope;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshots fetched during pre-login, waiting for PlayerJoinEvent. */
public final class PendingSnapshots {

    public record Pending(SnapshotEnvelope envelope, long seq, long createdAt, ApplyService.Prepared prepared) {
    }

    /** Entries older than this are dropped on the next access. */
    private static final long MAX_AGE_MS = 60_000L;

    /** Hard cap so cancelled or timed-out joins cannot accumulate decoded snapshots. */
    private static final int MAX_ENTRIES = 256;

    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

    /** Stores a pending snapshot. Returns false when the table is full and the value was dropped. */
    public boolean put(UUID uuid, Pending value) {
        evictExpired();
        if (pending.size() >= MAX_ENTRIES) {
            // Every entry here is still waiting for its join; dropping the oldest one would let that
            // player enter with stale data and never get an ACK. Refuse the newcomer instead, which
            // the pre-login handler turns into a refused login.
            return false;
        }
        pending.put(uuid, value);
        return true;
    }

    public Pending remove(UUID uuid) {
        Pending value = pending.remove(uuid);
        if (value != null && value.createdAt() < System.currentTimeMillis() - MAX_AGE_MS) {
            return null;
        }
        return value;
    }

    /** Drops a staged entry without treating it as an applied snapshot (used on quit). */
    public void discard(UUID uuid) {
        pending.remove(uuid);
    }

    /**
     * Expires entries whose login never completed (cancelled login, kicked client). They are not
     * reported anywhere: an expired entry belongs to a player who is not joining.
     */
    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        pending.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff);
    }

    public int size() {
        return pending.size();
    }
}
