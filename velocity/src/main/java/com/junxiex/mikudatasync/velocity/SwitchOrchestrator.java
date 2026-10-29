package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.Frame;
import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.Metrics;
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
import java.util.concurrent.TimeUnit;

/**
 * Switch gate: before a player is allowed to connect to the target server, ask the source server
 * for a final snapshot and wait until it has been committed by the proxy journal.
 */
public final class SwitchOrchestrator {

    /** Grace window before the abort is sent, so an in-flight PUT can still be attributed. */
    private static final long ABORT_GRACE_MS = 750L;

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
        IpcServer.Handoff handoff = ipcServer.startHandoff(sourceServer, uuid, config.switchTimeoutMs());
        long gateId = handoff.gateId();
        CompletableFuture<Frame> request = handoff.future();
        request.whenComplete((frame, error) -> {
            try {
                if (error != null) {
                    handleFailure(event, player, sourceServer, error);
                } else {
                    logger.info("Snapshot gate passed for {} (source={})", player.getUsername(), sourceServer);
                }
            } finally {
                if (error == null) {
                    switching.remove(uuid);
                } else {
                    // The backend may still be capturing and sending its PUT when the gate times out.
                    // The abort is therefore delayed by a short grace window and then evaluated after
                    // the disk queue drains, so it reports the state the store actually reached. The
                    // switch lock is held until that answer is sent: a retry in between would start a
                    // second handoff whose gate the late PUT could be mistaken for.
                    CompletableFuture.delayedExecutor(ABORT_GRACE_MS, TimeUnit.MILLISECONDS)
                            .execute(() -> {
                                try {
                                    ipcServer.afterPendingCommits(() ->
                                            ipcServer.send(sourceServer, Opcode.HANDOFF_ABORT,
                                                    IpcPayloads.abort(uuid, isCommitted(uuid, gateId))));
                                } finally {
                                    switching.remove(uuid);
                                }
                            });
                }
                Metrics.recordGate(System.nanoTime() - gateStart);
                gate.complete(null);
            }
        });
        return EventTask.resumeWhenComplete(gate);
    }

    private boolean isCommitted(UUID uuid, long gateId) {
        return ipcServer.wasCommitted(uuid, gateId);
    }

    private void handleFailure(ServerPreConnectEvent event, Player player, String sourceServer, Throwable error) {
        String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (!config.failClosed()) {
            logger.warn("Allowing switch for {} without confirmed snapshot (fail-open): {}",
                    player.getUsername(), reason);
            return;
        }
        if (ipcServer.isConnected(sourceServer)) {
            logger.warn("Denying switch for {} (source={}): {}", player.getUsername(), sourceServer, reason);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            if (config.kickOnFailure()) {
                // fail-mode: kick means the player must not stay on a server whose snapshot could not
                // be confirmed; disconnecting is the only way to guarantee that.
                player.disconnect(net.kyori.adventure.text.Component.text(
                        "跨服数据同步失败，请稍后重新连接"));
            }
            return;
        }
        // The source backend is gone (crash, restart, network loss). Denying here also denied the
        // proxy's own fallback redirect, so every player on that server was stranded and finally
        // kicked. The last committed snapshot is the best data available; let them leave.
        logger.warn("Allowing switch for {} because source server {} is not connected ({}); "
                + "using the last committed snapshot", player.getUsername(), sourceServer, reason);
    }
}
