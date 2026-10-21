package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.Frame;
import com.junxiex.mikudatasync.common.Metrics;
import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Opcode;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Switch gate: before a player is allowed to connect to the target server, ask the source server
 * for a final snapshot and wait until it has been committed by the proxy journal.
 */
public final class SwitchOrchestrator {

    private final Logger logger;
    private final VelocityConfig config;
    private final IpcServer ipcServer;
    private final Set<UUID> switching = ConcurrentHashMap.newKeySet();

    public SwitchOrchestrator(Logger logger, VelocityConfig config, IpcServer ipcServer) {
        this.logger = logger;
        this.config = config;
        this.ipcServer = ipcServer;
    }

    @Subscribe
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty()) {
            return null;
        }
        String sourceServer = current.get().getServerInfo().getName();
        UUID uuid = player.getUniqueId();
        if (!switching.add(uuid)) {
            logger.warn("Denying overlapping switch for {}", player.getUsername());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return null;
        }

        long gateStart = System.nanoTime();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Frame> request;
        try {
            request = ipcServer.request(sourceServer, Opcode.FLUSH_REQ, IpcPayloads.uuid(uuid),
                    config.switchTimeoutMs());
        } catch (Exception e) {
            switching.remove(uuid);
            handleFailure(event, player, sourceServer, e);
            return null;
        }
        request.whenComplete((frame, error) -> {
            try {
                if (error != null) {
                    handleFailure(event, player, sourceServer, error);
                } else {
                    logger.info("Snapshot gate passed for {} (source={})", player.getUsername(), sourceServer);
                }
            } finally {
                if (error != null) {
                    ipcServer.send(sourceServer, Opcode.HANDOFF_ABORT, IpcPayloads.uuid(uuid));
                }
                switching.remove(uuid);
                Metrics.recordGate(System.nanoTime() - gateStart);
                gate.complete(null);
            }
        });
        return EventTask.resumeWhenComplete(gate);
    }

    private void handleFailure(ServerPreConnectEvent event, Player player, String sourceServer, Throwable error) {
        String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (config.failClosed()) {
            logger.warn("Denying switch for {} (source={}): {}", player.getUsername(), sourceServer, reason);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        } else {
            logger.warn("Allowing switch for {} without confirmed snapshot (fail-open): {}",
                    player.getUsername(), reason);
        }
    }
}
