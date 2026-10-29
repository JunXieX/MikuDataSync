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
        version = "1.7.0",
        description = "Cross-server player data synchronization",
        authors = {"JunXieX"},
        url = "https://github.com/JunXieX/MikuDataSync"
)
public final class MikuDataSyncVelocity {



    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityConfig config;
    private SnapshotStore store;
    private IpcServer ipcServer;
    private String startFailure;
    private MdsCommand command;

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
            if (config.warning() != null) {
                logger.warn(config.warning());
            }
            JournalStore journal = new JournalStore(
                    dataDirectory.resolve(config.journalFile()),
                    config.fsyncMode(),
                    config.fsyncIntervalMs(),
                    config.compactionThresholdBytes(),
                    logger);
            store = new SnapshotStore(config.maxSnapshotBytes(), journal, config.retainOfflineBytes());
            java.util.Set<String> knownServers = server.getAllServers().stream()
                    .map(registered -> registered.getServerInfo().getName())
                    .collect(java.util.stream.Collectors.toSet());
            ipcServer = new IpcServer(logger, config, store, knownServers);
            ipcServer.start();

            server.getEventManager().register(this, new ProxyListener(this, server, logger, store, config));
            server.getEventManager().register(this, new SwitchOrchestrator(logger, config, ipcServer));
            long flushSeconds = Math.max(1, config.periodicFlushSeconds());
            server.getScheduler()
                    .buildTask(this, () -> {
                        store.forceJournal();
                        store.maybeCompact();
                    })
                    .repeat(flushSeconds, TimeUnit.SECONDS)
                    .schedule();
            var meta = server.getCommandManager().metaBuilder("mds").plugin(this).build();
            command = new MdsCommand(this, server, store, ipcServer, config,
                    new BackupService(dataDirectory.resolve("backups"), config.backupsKeep(), logger));
            server.getCommandManager().register(meta, command);
            logger.info("MikuDataSync enabled (protocol {}, journal {})",
                    com.junxiex.mikudatasync.common.Protocol.PROTOCOL_VERSION, store.journalStatus());
        } catch (Exception e) {
            logger.error("MikuDataSync failed to start; cross-server sync is disabled", e);
            // Release whatever was already opened so a later restart is not blocked by a bound port
            // or an open journal, and keep the reason visible through /mds doctor.
            startFailure = String.valueOf(e.getMessage());
            if (command != null) {
                command.close();
            }
            if (ipcServer != null) {
                ipcServer.close();
            }
            if (store != null) {
                store.close();
            }
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (command != null) {
            command.close();
        }
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

    public String startFailure() {
        return startFailure;
    }
}
