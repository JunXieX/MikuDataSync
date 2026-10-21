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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Blocking-socket IPC server: one reader thread per backend connection, one accept thread.
 * Supports proxy-initiated requests (FLUSH_REQ -> PUT response) with per-request futures.
 */
public final class IpcServer implements AutoCloseable {

    private static final long HANDSHAKE_WINDOW_MS = 30_000L;

    private final Logger logger;
    private final VelocityConfig config;
    private final SnapshotStore store;
    private final UUID nodeId = UUID.randomUUID();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mds-ipc-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public IpcServer(Logger logger, VelocityConfig config, SnapshotStore store) {
        this.logger = logger;
        this.config = config;
        this.store = store;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.bindHost(), config.bindPort()));
        acceptThread = new Thread(this::acceptLoop, "mds-ipc-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("MikuDataSync IPC listening on {}:{}", config.bindHost(), config.bindPort());
    }

    public boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
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
            return CompletableFuture.failedFuture(new IOException("backend not connected: " + serverId));
        }
        long requestId = requestIds.incrementAndGet();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        pendingRequests.put(requestId, new PendingRequest(serverId, future));
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

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                workers.execute(() -> handle(socket));
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
            socket.setSoTimeout(config.readTimeoutMs());
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            Frame hello = FrameCodec.read(in, Protocol.MAX_FRAME_BYTES);
            if (hello.opcode() != Opcode.HELLO) {
                send(out, Opcode.ERROR, hello.requestId(), errorPayload("expected HELLO"));
                return;
            }
            String serverId = authenticate(hello.payload());
            if (serverId == null) {
                send(out, Opcode.ERROR, hello.requestId(), errorPayload("authentication failed"));
                return;
            }

            connection = new Connection(serverId, socket, out);
            Connection previous = connections.put(serverId, connection);
            if (previous != null) {
                previous.close();
            }
            send(out, Opcode.HELLO_ACK, hello.requestId(), new ByteWriter(24)
                    .writeInt(Protocol.PROTOCOL_VERSION)
                    .writeUtf(nodeId.toString())
                    .toByteArray());
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
                failPendingForServer(connection.serverId());
                logger.info("IPC backend disconnected: {}", connection.serverId());
            }
        }
    }

    private String authenticate(byte[] payload) {
        try {
            ByteReader reader = new ByteReader(payload);
            int protocolVersion = reader.readInt();
            if (protocolVersion != Protocol.PROTOCOL_VERSION) {
                return null;
            }
            String serverId = reader.readUtf(128);
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
            return HmacAuth.verifyHex(config.secret(), message, hmac) ? serverId : null;
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
                SnapshotStore.Entry entry = store.get(uuid);
                byte[] response = entry == null
                        ? IpcPayloads.snapshotMissing()
                        : IpcPayloads.snapshotFound(new SnapshotMessage(entry.seq(), entry.captureSeq(),
                        entry.saveCause(), entry.sourceServer(), entry.envelope()));
                connection.send(Opcode.SNAPSHOT, frame.requestId(), response);
            }
            case PUT -> {
                UUID[] uuid = new UUID[1];
                SnapshotMessage message = IpcPayloads.readPutRequest(frame.payload(), uuid);
                try {
                    long seq = store.commit(uuid[0], message);
                    connection.send(Opcode.PUT_OK, frame.requestId(), IpcPayloads.putOk(seq));
                    completePending(frame.requestId(), frame);
                } catch (IOException e) {
                    connection.send(Opcode.NACK, frame.requestId(),
                            new NackMessage(uuid[0], 0L, "commit_failed", String.valueOf(e.getMessage())).encode());
                    failPending(frame.requestId(), e);
                }
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
            case ERROR -> logger.warn("IPC error from {}: {}", connection.serverId(),
                    new ByteReader(frame.payload()).readUtf(512));
            default -> connection.send(Opcode.ERROR, frame.requestId(),
                    errorPayload("unexpected opcode: " + frame.opcode()));
        }
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

    private void failPendingForServer(String serverId) {
        for (Long requestId : pendingRequests.keySet()) {
            PendingRequest pending = pendingRequests.get(requestId);
            if (pending != null && pending.serverId().equals(serverId)) {
                pendingRequests.remove(requestId);
                pending.future().completeExceptionally(new IOException("backend disconnected: " + serverId));
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
        connections.values().forEach(Connection::close);
        workers.shutdownNow();
    }

    private record PendingRequest(String serverId, CompletableFuture<Frame> future) {
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
