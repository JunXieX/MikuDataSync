package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ByteReader;
import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.Frame;
import com.junxiex.mikudatasync.common.FrameCodec;
import com.junxiex.mikudatasync.common.HmacAuth;
import com.junxiex.mikudatasync.common.IpcPayloads;
import com.junxiex.mikudatasync.common.NackMessage;
import com.junxiex.mikudatasync.common.Opcode;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Blocking-socket IPC server: one reader thread per backend connection, one accept thread.
 * Supports proxy-initiated requests (FLUSH_REQ -> PUT response) with per-request futures.
 */
public final class IpcServer implements AutoCloseable {

    private static final long HANDSHAKE_WINDOW_MS = 30_000L;

    /** Timeout for the HELLO frame only; a peer that never sends it must not hold a worker. */
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    /** Hard cap on concurrent backend connections (one per backend server in practice). */
    private static final int MAX_CONNECTIONS = 64;

    private final Logger logger;
    private final VelocityConfig config;
    private final SnapshotStore store;
    private final Set<String> knownServers;
    private final UUID nodeId = UUID.randomUUID();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingFlushes = new ConcurrentHashMap<>();
    /** Commits that landed after their request had already timed out (see wasCommitted). */
    private final Map<UUID, Long> lateCommits = new ConcurrentHashMap<>();
    /** Start time of the handoff currently in flight for a player, used to attribute late PUTs. */
    private final Map<UUID, Long> flushRequests = new ConcurrentHashMap<>();
    /** Request id of the FLUSH_REQ that is still waiting for its handoff snapshot. */
    private final Map<UUID, Long> flushRequestIds = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mds-ipc-worker");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * Replies run on their own pool: the reader threads are blocked in socket reads and never return
     * on shutdown, so sharing a pool with them would make the drain below drop queued acknowledgements.
     */
    private final ExecutorService repliers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mds-ipc-reply");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService diskIo = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mds-ipc-disk");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public IpcServer(Logger logger, VelocityConfig config, SnapshotStore store, Set<String> knownServers) {
        this.logger = logger;
        this.config = config;
        this.store = store;
        this.knownServers = Set.copyOf(knownServers);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.bindHost(), config.bindPort()));
        acceptThread = new Thread(this::acceptLoop, "mds-ipc-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("MikuDataSync IPC listening on {}:{}", config.bindHost(), config.bindPort());
        if (!config.bindHost().startsWith("127.") && !"localhost".equalsIgnoreCase(config.bindHost())
                && !"::1".equals(config.bindHost())) {
            logger.warn("IPC is bound to {}; restrict it to a private network and keep protocol.secret private",
                    config.bindHost());
        }
    }

    public int boundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    /** True while a backend with this server id holds an authenticated connection. */
    public boolean isConnected(String serverId) {
        return connections.containsKey(serverId);
    }

    /**
     * Resolves whether the handoff that just failed actually reached the store. Only two facts can
     * prove it: a commit that landed after its request had already timed out, or a commit whose
     * capture sequence matches the handoff that was in flight for this player. A snapshot left over
     * from an earlier switch must never count, or the cursor item would be handed out twice.
     */
    public boolean wasCommitted(String serverId, UUID uuid) {
        Long handoffStart = flushRequests.remove(uuid);
        if (lateCommits.remove(uuid) != null) {
            return true;
        }
        if (handoffStart == null) {
            // No handoff was in flight for this player, so nothing of this switch can be in the store.
            return false;
        }
        try {
            SnapshotStore.Entry entry = store.get(uuid);
            if (entry == null || entry.seq() <= 0L || !serverId.equals(entry.sourceServer())
                    || !"switch".equalsIgnoreCase(entry.saveCause())) {
                return false;
            }
            // Only a switch record written after this handoff started may count. An older switch
            // record would make the caller hand the cursor item out twice.
            return entry.updatedAt() >= handoffStart;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Runs {@code task} after every commit queued so far has finished (used by the abort path). When
     * the disk executor is already shut down the task runs on the caller's thread: it only reads the
     * store, so it cannot deadlock against a commit.
     */
    public void afterPendingCommits(Runnable task) {
        try {
            diskIo.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            task.run();
        }
    }

    public void send(String serverId, Opcode opcode, byte[] payload) {
        Connection connection = connections.get(serverId);
        if (connection == null) {
            return;
        }
        try {
            connection.send(opcode, 0L, payload);
        } catch (IOException e) {
            logger.warn("Failed to send {} to {}", opcode, serverId, e);
        }
    }

    public CompletableFuture<Frame> request(String serverId, Opcode opcode, byte[] payload, long timeoutMs) {
        Connection connection = connections.get(serverId);
        if (connection == null) {
            return CompletableFuture.failedFuture(new IOException(
                    "backend not connected: " + serverId + " (proxy.server-id must match the Velocity server name)"));
        }
        long requestId = requestIds.incrementAndGet();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        pendingRequests.put(requestId, new PendingRequest(connection, future));
        if (opcode == Opcode.FLUSH_REQ) {
            try {
                UUID uuid = IpcPayloads.parseUuid(payload);
                CompletableFuture<Void> registered = new CompletableFuture<>();
                pendingFlushes.put(uuid, registered);
                // Remember when this handoff started: a PUT that arrives after the gate timed out
                // still has to be attributed to it, and only a snapshot written after this instant
                // can be the one that carries the cursor item.
                flushRequests.put(uuid, System.currentTimeMillis());
                flushRequestIds.put(uuid, requestId);
                future.whenComplete((frame, error) -> {
                    pendingFlushes.remove(uuid, registered);
                    flushRequestIds.remove(uuid, requestId);
                    if (error != null) {
                        // A handoff that failed before its commit must not leave its start time behind:
                        // the abort path reads it back to attribute a commit.
                        flushRequests.remove(uuid);
                        registered.completeExceptionally(error);
                    } else {
                        // Committed; the gate consumed the start time.
                        flushRequests.remove(uuid);
                        registered.complete(null);
                    }
                });
            } catch (ProtocolException ignored) {
            }
        }
        try {
            connection.send(opcode, requestId, payload);
        } catch (IOException e) {
            pendingRequests.remove(requestId);
            future.completeExceptionally(e);
            return future;
        }
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((frame, error) -> pendingRequests.remove(requestId));
    }

    /** Runs blocking disk work off the backend reader threads. */
    public void runOnDisk(Runnable task) {
        try {
            diskIo.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("Disk executor is closed; dropping task");
        }
    }

    /** Queues a reply on a worker thread so socket writes never block the disk executor. */
    private void reply(Connection connection, Runnable task, long requestId) {
        try {
            repliers.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The frame cannot be written any more, but the waiting request still has to be settled
            // or it hangs until its own timeout.
            logger.debug("Worker pool is closed; settling request {} without a reply", requestId);
            failPending(requestId, new IOException("proxy is shutting down"));
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (connectionCount.incrementAndGet() > MAX_CONNECTIONS) {
                    connectionCount.decrementAndGet();
                    logger.warn("Rejecting IPC connection from {}: connection limit reached",
                            socket.getRemoteSocketAddress());
                    socket.close();
                    continue;
                }
                socket.setTcpNoDelay(true);
                workers.execute(() -> {
                    try {
                        handle(socket);
                    } finally {
                        connectionCount.decrementAndGet();
                    }
                });
            } catch (IOException e) {
                if (!closed.get()) {
                    logger.warn("IPC accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        Connection connection = null;
        try (socket) {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            Frame hello = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
            if (hello.opcode() != Opcode.HELLO) {
                send(out, Opcode.ERROR, hello.requestId(), errorPayload("expected HELLO"));
                return;
            }
            String serverId = authenticate(hello.payload());
            if (serverId == null) {
                send(out, Opcode.ERROR, hello.requestId(),
                        errorPayload("authentication failed: check protocol.secret and that both sides run "
                                + "protocol version " + Protocol.PROTOCOL_VERSION));
                return;
            }
            socket.setSoTimeout(config.readTimeoutMs());

            connection = new Connection(serverId, socket, out);
            Connection previous = connections.put(serverId, connection);
            if (previous != null) {
                previous.close();
            }
            send(out, Opcode.HELLO_ACK, hello.requestId(), new ByteWriter(24)
                    .writeInt(Protocol.PROTOCOL_VERSION)
                    .writeUtf(nodeId.toString())
                    .toByteArray());
            if (!knownServers.isEmpty() && !knownServers.contains(serverId)) {
                logger.warn("Backend server-id '{}' does not match any Velocity server name {}; switches to/from it will fail",
                        serverId, knownServers);
            }
            logger.info("IPC backend connected: {}", serverId);

            while (!closed.get()) {
                Frame frame = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
                dispatch(connection, frame);
            }
        } catch (EOFException | SocketException ignored) {
            // clean disconnect
        } catch (Exception e) {
            if (!closed.get()) {
                logger.warn("IPC connection error", e);
            }
        } finally {
            if (connection != null) {
                connections.remove(connection.serverId(), connection);
                connection.close();
                failPendingForServer(connection);
                logger.info("IPC backend disconnected: {}", connection.serverId());
            }
        }
    }

    private String authenticate(byte[] payload) {
        try {
            ByteReader reader = new ByteReader(payload);
            int protocolVersion = reader.readInt();
            if (protocolVersion != Protocol.PROTOCOL_VERSION) {
                logger.warn("IPC handshake rejected from '{}': protocol version {} does not match {}; "
                        + "upgrade the proxy and every backend together", reader.readUtf(Protocol.MAX_SERVER_ID_BYTES),
                        protocolVersion, Protocol.PROTOCOL_VERSION);
                return null;
            }
            String serverId = reader.readUtf(Protocol.MAX_SERVER_ID_BYTES);
            long timestamp = reader.readLong();
            String nonce = reader.readUtf(128);
            String hmac = reader.readUtf(256);
            if (reader.remaining() != 0 || serverId.isEmpty()) {
                return null;
            }
            if (Math.abs(System.currentTimeMillis() - timestamp) > HANDSHAKE_WINDOW_MS) {
                return null;
            }
            String message = serverId + "|" + timestamp + "|" + nonce + "|" + protocolVersion;
            if (!HmacAuth.verifyHex(config.secret(), message, hmac)) {
                logger.warn("IPC handshake rejected for '{}': HMAC mismatch (protocol.secret differs between "
                        + "proxy and backend)", serverId);
                return null;
            }
            return serverId;
        } catch (ProtocolException e) {
            return null;
        }
    }

    private void dispatch(Connection connection, Frame frame) throws IOException, ProtocolException {
        switch (frame.opcode()) {
            case PING -> connection.send(Opcode.PONG, frame.requestId(),
                    new ByteWriter(8).writeLong(System.currentTimeMillis()).toByteArray());
            case GET -> {
                UUID uuid = IpcPayloads.parseUuid(frame.payload());
                CompletableFuture<Void> flush = pendingFlushes.get(uuid);
                if (flush == null) {
                    sendSnapshot(connection, frame.requestId(), uuid);
                } else {
                    long requestId = frame.requestId();
                    // Copy before adding a timeout: orTimeout completes the shared instance, which
                    // would make every later GET on this player fail instantly and read stale data.
                    CompletableFuture<Void> wait = flush.copy().orTimeout(config.getWaitMs(), TimeUnit.MILLISECONDS);
                    wait.whenComplete((ignored, error) -> {
                                try {
                                    sendSnapshot(connection, requestId, uuid);
                                } catch (IOException e) {
                                    logger.warn("Failed to send delayed snapshot response", e);
                                }
                            });
                }
            }
            case PUT -> {
                UUID[] uuid = new UUID[1];
                SnapshotMessage message = IpcPayloads.readPutRequest(frame.payload(), uuid);
                UUID player = uuid[0];
                long requestId = frame.requestId();
                // Committing touches the journal (fsync for switch/quit), so it runs on the disk
                // executor: doing it inline blocked this backend's reader thread, and a slow disk
                // then delayed every other player's switch on the same backend.
                runOnDisk(() -> {
                    long seq;
                    try {
                        seq = store.commit(player, message);
                    } catch (Exception e) {
                        logger.warn("Snapshot commit failed for {}", player, e);
                        Long gateId = flushRequestIds.remove(player);
                        if (gateId != null) {
                            failPending(gateId, e);
                        }
                        // The reply must not be written from the disk thread: one blocked socket
                        // would then serialise every other backend's commit behind it.
                        reply(connection, () -> {
                            try {
                                connection.send(Opcode.NACK, requestId, new NackMessage(player, 0L,
                                        "commit_failed", String.valueOf(e.getMessage())).encode());
                            } catch (IOException sendFailure) {
                                logger.warn("Failed to send NACK to {}", connection.serverId(), sendFailure);
                            }
                        }, requestId);
                        return;
                    }
                    if ("switch".equalsIgnoreCase(message.saveCause())) {
                        Long gateId = flushRequestIds.remove(player);
                        if (gateId != null) {
                            // The handoff snapshot is durable: release the gate that waits for it.
                            completePending(gateId, frame);
                        } else {
                            // The gate already timed out, but the commit landed: the abort path must
                            // not hand the cursor item back a second time.
                            lateCommits.put(player, System.currentTimeMillis());
                            // Age-bounded on every write: a stale mark for this player would otherwise
                            // be consumed by a later, unrelated abort and report the wrong state.
                            long cutoff = System.currentTimeMillis() - 60_000L;
                            lateCommits.entrySet().removeIf(entry -> entry.getValue() < cutoff);
                        }
                        flushRequests.remove(player);
                    }
                    reply(connection, () -> {
                        try {
                            connection.send(Opcode.PUT_OK, requestId, IpcPayloads.putOk(seq));
                        } catch (IOException e) {
                            logger.warn("Failed to send PUT_OK to {}", connection.serverId(), e);
                        }
                    }, requestId);
                });
            }
            case ACK -> {
                UUID[] uuid = new UUID[1];
                long appliedSeq = IpcPayloads.readAck(frame.payload(), uuid);
                store.ack(uuid[0], appliedSeq);
            }
            case NACK -> {
                NackMessage nack = NackMessage.decode(frame.payload());
                logger.warn("Backend apply failure for {} at seq {}: {} - {}",
                        nack.uuid(), nack.appliedSeq(), nack.code(), nack.message());
            }
            case BACKUP_RES, RESTORE_RES -> completePending(frame.requestId(), frame);
            case ERROR -> {
                String message = new ByteReader(frame.payload()).readUtf(512);
                logger.warn("IPC error from {}: {}", connection.serverId(), message);
                failPending(frame.requestId(), new IOException("backend error: " + message));
            }
            default -> connection.send(Opcode.ERROR, frame.requestId(),
                    errorPayload("unexpected opcode: " + frame.opcode()));
        }
    }

    private void sendSnapshot(Connection connection, long requestId, UUID uuid) throws IOException {
        SnapshotStore.Entry entry;
        try {
            entry = store.get(uuid);
        } catch (IOException e) {
            logger.error("Stored snapshot for {} is unreadable; refusing to answer GET", uuid, e);
            connection.send(Opcode.ERROR, requestId, errorPayload("stored snapshot is unreadable"));
            return;
        }
        byte[] response = entry == null
                ? IpcPayloads.snapshotMissing()
                : IpcPayloads.snapshotFound(new SnapshotMessage(entry.seq(), entry.captureSeq(),
                entry.saveCause(), entry.sourceServer(), entry.envelope()));
        connection.send(Opcode.SNAPSHOT, requestId, response);
    }

    private void completePending(long requestId, Frame frame) {
        if (requestId == 0L) {
            return;
        }
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending != null) {
            pending.future().complete(frame);
        }
    }

    private void failPending(long requestId, Throwable error) {
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending != null) {
            pending.future().completeExceptionally(error);
        }
    }

    /**
     * Fails only the requests that belong to this connection. Matching on the server id alone would
     * also kill requests issued by a newer connection that replaced this one during a reconnect.
     */
    private void failPendingForServer(Connection connection) {
        for (Long requestId : pendingRequests.keySet()) {
            PendingRequest pending = pendingRequests.get(requestId);
            if (pending != null && pending.connection() == connection) {
                pendingRequests.remove(requestId);
                pending.future().completeExceptionally(
                        new IOException("backend disconnected: " + connection.serverId()));
            }
        }
    }

    private static byte[] errorPayload(String message) {
        return new ByteWriter(64).writeUtf(message).toByteArray();
    }

    private static void send(DataOutputStream out, Opcode opcode, long requestId, byte[] payload) throws IOException {
        FrameCodec.write(out, new Frame(opcode, requestId, payload));
    }

    @Override
    public void close() {
        closed.set(true);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        // Commits are drained before the sockets close: a commit that finished after its connection
        // was closed would still persist the data but its acknowledgement would be lost.
        diskIo.shutdown();
        try {
            if (!diskIo.awaitTermination(2, TimeUnit.SECONDS)) {
                diskIo.shutdownNow();
            }
        } catch (InterruptedException e) {
            diskIo.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Replies queued by the commits above still have to reach their backend, so the reply pool is
        // drained before any socket is closed; otherwise the data is durable but the sender only sees
        // a timeout.
        repliers.shutdown();
        try {
            if (!repliers.awaitTermination(2, TimeUnit.SECONDS)) {
                repliers.shutdownNow();
            }
        } catch (InterruptedException e) {
            repliers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        connections.values().forEach(Connection::close);
        // Reader threads are blocked in socket reads; closing the sockets above is what releases them.
        workers.shutdownNow();
    }

    private record PendingRequest(Connection connection, CompletableFuture<Frame> future) {
    }

    private static final class Connection {

        private final String serverId;
        private final Socket socket;
        private final DataOutputStream out;

        private Connection(String serverId, Socket socket, DataOutputStream out) {
            this.serverId = serverId;
            this.socket = socket;
            this.out = out;
        }

        private String serverId() {
            return serverId;
        }

        private synchronized void send(Opcode opcode, long requestId, byte[] payload) throws IOException {
            if (socket.isClosed()) {
                throw new IOException("connection closed");
            }
            FrameCodec.write(out, new Frame(opcode, requestId, payload));
        }

        private void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
