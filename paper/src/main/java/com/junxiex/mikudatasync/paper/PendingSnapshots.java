package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.SnapshotEnvelope;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshots fetched during pre-login, waiting for PlayerJoinEvent. */
public final class PendingSnapshots {

    public record Pending(SnapshotEnvelope envelope, long seq) {
    }

    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

    public void put(UUID uuid, Pending value) {
        pending.put(uuid, value);
    }

    public Pending remove(UUID uuid) {
        return pending.remove(uuid);
    }

    public int size() {
        return pending.size();
    }
}
