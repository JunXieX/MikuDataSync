package com.junxiex.mikudatasync.velocity.listener;

import com.junxiex.mikudatasync.velocity.SnapshotStore;
import com.velocitypowered.api.proxy.ProxyServer;
import com.junxiex.mikudatasync.velocity.VelocityConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.slf4j.Logger;

/** Evicts offline snapshots from memory when they are already durable in the journal. */
public final class ProxyListener {

    private final ProxyServer server;
    private final Logger logger;
    private final SnapshotStore store;
    private final VelocityConfig config;

    public ProxyListener(ProxyServer server, Logger logger, SnapshotStore store, VelocityConfig config) {
        this.server = server;
        this.logger = logger;
        this.store = store;
        this.config = config;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (config.retainOffline()) {
            return;
        }
        var uuid = event.getPlayer().getUniqueId();
        // Delay eviction so a late quit PUT can land and a quick reconnect still sees the snapshot.
        java.util.concurrent.CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> {
                    if (server.getPlayer(uuid).isEmpty()) {
                        store.evict(uuid);
                    }
                });
        logger.debug("Scheduled snapshot eviction for {}", event.getPlayer().getUsername());
    }
}
