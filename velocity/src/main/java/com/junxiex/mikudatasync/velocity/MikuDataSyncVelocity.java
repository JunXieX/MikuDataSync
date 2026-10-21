package com.junxiex.mikudatasync.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.junxiex.mikudatasync.velocity.command.MdsCommand;
import com.junxiex.mikudatasync.velocity.listener.ProxyListener;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "mikudatasync",
        name = "MikuDataSync",
        version = "1.0.14",
        description = "Cross-server player data synchronization",
        authors = {"JunXieX"},
        url = "https://github.com/JunXieX/MikuDataSync"
)
public final class MikuDataSyncVelocity {

    private static final long COMPACTION_THRESHOLD_BYTES = 64L * 1024 * 1024;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityConfig config;
    private SnapshotStore store;
    private IpcServer ipcServer;

    @Inject
    public MikuDataSyncVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = VelocityConfig.load(dataDirectory);
            JournalStore journal = new JournalStore(
                    dataDirectory.resolve(config.journalFile()),
                    config.fsyncMode(),
                    config.fsyncIntervalMs(),
                    COMPACTION_THRESHOLD_BYTES);
            store = new SnapshotStore(config.maxSnapshotBytes(), journal);
            ipcServer = new IpcServer(logger, config, store);
            ipcServer.start();

            server.getEventManager().register(this, new ProxyListener(logger, store, config));
            server.getEventManager().register(this, new SwitchOrchestrator(logger, config, ipcServer));
            server.getScheduler()
                    .buildTask(this, () -> {
                        store.forceJournal();
                        store.maybeCompact();
                    })
                    .repeat(30L, TimeUnit.SECONDS)
                    .schedule();
            var meta = server.getCommandManager().metaBuilder("mds").plugin(this).build();
            server.getCommandManager().register(meta, new MdsCommand(server, store, ipcServer, config));
            logger.info("MikuDataSync enabled (protocol {}, journal {})",
                    com.junxiex.mikudatasync.common.Protocol.PROTOCOL_VERSION, store.journalStatus());
        } catch (Exception e) {
            logger.error("MikuDataSync failed to start; cross-server sync is disabled", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (ipcServer != null) {
            ipcServer.close();
        }
        if (store != null) {
            store.close();
        }
    }

    public Logger logger() {
        return logger;
    }

    public SnapshotStore store() {
        return store;
    }
}
