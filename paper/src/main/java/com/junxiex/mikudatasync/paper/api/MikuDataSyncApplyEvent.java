package com.junxiex.mikudatasync.paper.api;

import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired right before a snapshot is applied to a joining player (read-only notification). */
public final class MikuDataSyncApplyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final SnapshotEnvelope envelope;

    public MikuDataSyncApplyEvent(Player player, SnapshotEnvelope envelope) {
        this.player = player;
        this.envelope = envelope;
    }

    public Player player() {
        return player;
    }

    public SnapshotEnvelope envelope() {
        return envelope;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
