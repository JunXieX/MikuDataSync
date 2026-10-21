package com.junxiex.mikudatasync.paper.api;

import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a player snapshot has been captured (read-only notification). */
public final class MikuDataSyncCaptureEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String cause;
    private final SnapshotMessage snapshot;

    public MikuDataSyncCaptureEvent(Player player, String cause, SnapshotMessage snapshot) {
        this.player = player;
        this.cause = cause;
        this.snapshot = snapshot;
    }

    public Player player() {
        return player;
    }

    public String cause() {
        return cause;
    }

    public SnapshotMessage snapshot() {
        return snapshot;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
