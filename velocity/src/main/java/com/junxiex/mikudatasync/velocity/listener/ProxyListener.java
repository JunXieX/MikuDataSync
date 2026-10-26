package com.junxiex.mikudatasync.velocity.listener;

import com.junxiex.mikudatasync.velocity.MikuDataSyncVelocity;
import com.junxiex.mikudatasync.velocity.SnapshotStore;
import com.junxiex.mikudatasync.velocity.VelocityConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/** Evicts offline snapshots from memory when they are already durable in the journal. */
public final class ProxyListener {

    /** Delay before eviction so a late quit PUT can land and a quick reconnect still hits memory. */
    private static final long EVICT_DELAY_SECONDS = 30L;

    private final MikuDataSyncVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final SnapshotStore store;
    private final VelocityConfig config;

    public ProxyListener(MikuDataSyncVelocity plugin, ProxyServer server, Logger logger,
                         SnapshotStore store, VelocityConfig config) {
        this.plugin = plugin;
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
        // Scheduled on the proxy scheduler rather than the fork-join common pool so shutdown stays
        // orderly; a quick reconnect simply keeps the entry because the player is online again.
        server.getScheduler()
                .buildTask(plugin, () -> {
                    if (server.getPlayer(uuid).isEmpty()) {
                        store.evict(uuid);
                    }
                })
                .delay(EVICT_DELAY_SECONDS, TimeUnit.SECONDS)
                .schedule();
        logger.debug("Scheduled snapshot eviction for {}", event.getPlayer().getUsername());
    }
}
