package com.junxiex.mikudatasync.velocity.listener;

import com.junxiex.mikudatasync.velocity.SnapshotStore;
import com.junxiex.mikudatasync.velocity.VelocityConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.slf4j.Logger;

/** Evicts offline snapshots from memory when they are already durable in the journal. */
public final class ProxyListener {

    private final Logger logger;
    private final SnapshotStore store;
    private final VelocityConfig config;

    public ProxyListener(Logger logger, SnapshotStore store, VelocityConfig config) {
        this.logger = logger;
        this.store = store;
        this.config = config;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (config.retainOffline()) {
            return;
        }
        store.evict(event.getPlayer().getUniqueId());
        logger.debug("Evicted offline snapshot for {}", event.getPlayer().getUsername());
    }
}
