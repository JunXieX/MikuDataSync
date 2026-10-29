package com.junxiex.mikudatasync.paper;

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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Blocking-socket IPC client with automatic reconnect and a small request/response table.
 */
public final class IpcClient implements AutoCloseable {

    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CLOSED
    }

    private final Logger logger;
    private final PaperConfig config;
    private final ExecutorService io;
    private final ExecutorService writer;
    private final ScheduledExecutorService timer;
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<Frame>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("mds-ipc-watchdog"));

    private volatile long backoffMs = 1000L;
    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private volatile Consumer<Frame> frameHandler = frame -> {
    };

    public IpcClient(MikuDataSyncPaper plugin, PaperConfig config) {
        this.logger = plugin.getLogger();
        this.config = config;
        this.io = Executors.newSingleThreadExecutor(daemonFactory("mds-ipc-io"));
        // Responses (PUT/ACK) are produced on region/entity threads; writing a snapshot frame there
        // would block that region's tick whenever the proxy socket backs up.
        this.writer = Executors.newSingleThreadExecutor(daemonFactory("mds-ipc-writer"));
        this.timer = Executors.newSingleThreadScheduledExecutor(daemonFactory("mds-ipc-timer"));
    }

    public void start() {
        io.execute(this::runLoop);
        timer.scheduleWithFixedDelay(this::ping, 10L, 10L, TimeUnit.SECONDS);
    }

    public State state() {
        return state.get();
    }

    public CompletableFuture<SnapshotMessage> getSnapshot(UUID uuid, long timeoutMs) {
        return request(Opcode.GET, IpcPayloads.getRequest(uuid), timeoutMs).thenApply(frame -> {
            try {
                return IpcPayloads.readSnapshotResponse(frame.payload());
            } catch (ProtocolException e) {
                throw new CompletionException(e);
            }
        });
    }

    public CompletableFuture<Long> putSnapshot(UUID uuid, SnapshotMessage message, long timeoutMs) {
        return request(Opcode.PUT, IpcPayloads.putRequest(uuid, message), timeoutMs).thenApply(frame -> {
            try {
                return IpcPayloads.readPutOk(frame.payload());
            } catch (ProtocolException e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Waits for in-flight snapshot writes (used during shutdown). */
    public void awaitPending(long timeoutMs) {
        java.util.List<CompletableFuture<Frame>> futures = new java.util.ArrayList<>(pending.values());
        if (futures.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    /** Waits for queued frames to reach the socket (used during shutdown). */
    public void drainWrites(long timeoutMs) {
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        try {
            writer.execute(drained::countDown);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return;
        }
        try {
            drained.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void ack(UUID uuid, long appliedSeq) {
        respond(0L, Opcode.ACK, IpcPayloads.ack(uuid, appliedSeq));
    }

    private void runLoop() {
        while (!closed.get()) {
            try {
                connectAndServe();
            } catch (Exception e) {
                if (closed.get()) {
                    return;
                }
                state.set(State.DISCONNECTED);
                failPending(e);
                closeSocket();
                logger.warning("IPC connection failed: " + e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2L, 30000L);
            }
        }
    }

    private void connectAndServe() throws Exception {
        state.set(State.CONNECTING);
        Socket newSocket = new Socket();
        newSocket.connect(new InetSocketAddress(config.proxyHost(), config.proxyPort()), config.connectTimeoutMs());
        newSocket.setTcpNoDelay(true);
        newSocket.setSoTimeout(10_000);

        DataInputStream newIn = new DataInputStream(new BufferedInputStream(newSocket.getInputStream()));
        DataOutputStream newOut = new DataOutputStream(new BufferedOutputStream(newSocket.getOutputStream()));
        this.socket = newSocket;
        this.in = newIn;
        this.out = newOut;

        sendHello(newOut);
        Frame ack = FrameCodec.read(newIn, Protocol.MAX_FRAME_BYTES);
        if (ack.opcode() == Opcode.ERROR) {
            // Surface the proxy's reason: a version or secret mismatch otherwise looked like a
            // generic connection failure and every player was kicked with no clue in the log.
            throw new IOException("proxy rejected handshake: " + readErrorMessage(ack.payload())
                    + " (local protocol version " + Protocol.PROTOCOL_VERSION + ")");
        }
        if (ack.opcode() != Opcode.HELLO_ACK) {
            throw new IOException("unexpected handshake response: " + ack.opcode());
        }
        newSocket.setSoTimeout(0);
        // Reset the reconnect backoff only once a handshake actually succeeded. Leaving the last
        // doubled delay in place meant every later disconnect waited a flat 30s, during which
        // on-sync-unavailable: kick rejected every login on this backend.
        backoffMs = 1000L;
        state.set(State.CONNECTED);
        logger.info("Connected to proxy IPC at " + config.proxyHost() + ":" + config.proxyPort());

        while (!closed.get()) {
            Frame frame = FrameCodec.read(newIn, Protocol.MAX_FRAME_BYTES);
            onFrame(frame);
        }
    }

    private void sendHello(DataOutputStream newOut) throws IOException {
        String nonce = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        String message = config.serverId() + "|" + timestamp + "|" + nonce + "|" + Protocol.PROTOCOL_VERSION;
        String hmac = HmacAuth.signHex(config.secret(), message);
        byte[] payload = new ByteWriter(128)
                .writeInt(Protocol.PROTOCOL_VERSION)
                .writeUtf(config.serverId())
                .writeLong(timestamp)
                .writeUtf(nonce)
                .writeUtf(hmac)
                .toByteArray();
        FrameCodec.write(newOut, new Frame(Opcode.HELLO, 0L, payload));
    }

    private void onFrame(Frame frame) {
        CompletableFuture<Frame> future = pending.remove(frame.requestId());
        if (future != null) {
            // An ERROR frame that answers a request must fail it. Treating it as a normal response
            // made a failed snapshot read look like "this player has no snapshot", which silently
            // let the player join with stale data.
            if (frame.opcode() == Opcode.NACK) {
                try {
                    NackMessage nack = NackMessage.decode(frame.payload());
                    future.completeExceptionally(new IOException("backend NACK: " + nack.code() + " " + nack.message()));
                } catch (ProtocolException e) {
                    future.completeExceptionally(e);
                }
            } else if (frame.opcode() == Opcode.ERROR) {
                future.completeExceptionally(new IOException("backend error: " + readErrorMessage(frame.payload())));
            } else {
                future.complete(frame);
            }
            return;
        }
        if (frame.opcode() == Opcode.ERROR) {
            logger.warning("IPC error frame: " + readErrorMessage(frame.payload()));
            return;
        }
        frameHandler.accept(frame);
    }

    CompletableFuture<Frame> request(Opcode opcode, byte[] payload, long timeoutMs) {
        if (state.get() != State.CONNECTED) {
            CompletableFuture<Frame> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("IPC is not connected"));
            return failed;
        }
        // Every request id is allocated here, so the pending key is always the id that goes on the
        // wire and a reply can be matched without guessing.
        long id = requestIds.incrementAndGet();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        pending.put(id, future);
        // The frame is registered before it is queued, so the single writer thread keeps request
        // order without the caller thread ever blocking on a socket write.
        Frame frame = new Frame(opcode, id, payload);
        CompletableFuture<Frame> bounded = future.whenComplete((frame1, error) -> pending.remove(id));
        try {
            writer.execute(() -> {
                try {
                    writeFrame(frame);
                } catch (IOException e) {
                    pending.remove(id);
                    future.completeExceptionally(e);
                    return;
                }
                // The timeout starts once the frame is on the wire: queuing behind other writes must
                // not consume the caller's budget (a pre-login GET would otherwise time out unsent).
                future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pending.remove(id);
            future.completeExceptionally(new IOException("IPC client closed", e));
            return bounded;
        }
        return bounded;
    }

    /** A single frame write must not hold the writer thread longer than this. */
    private static final long WRITE_TIMEOUT_MS = 5_000L;

    private void writeFrame(Frame frame) throws IOException {
        synchronized (writeLock) {
            DataOutputStream current = out;
            if (current == null || state.get() != State.CONNECTED) {
                throw new IOException("IPC is not connected");
            }
            long deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS;
            Watchdog watchdog = Watchdog.arm(socket, deadline, logger);
            try {
                FrameCodec.write(current, frame);
            } finally {
                watchdog.cancel();
            }
        }
    }

    /**
     * Closes the socket when a single write exceeds its budget. A blocked write cannot be interrupted
     * otherwise, and the writer thread being stuck would silently stop every ACK and snapshot PUT.
     */
    private static final class Watchdog {

        private final java.util.concurrent.ScheduledFuture<?> task;

        private Watchdog(java.util.concurrent.ScheduledFuture<?> task) {
            this.task = task;
        }

        private static Watchdog arm(Socket socket, long deadline, Logger logger) {
            if (socket == null) {
                return new Watchdog(null);
            }
            long delay = Math.max(1L, deadline - System.currentTimeMillis());
            java.util.concurrent.ScheduledFuture<?> task = WATCHDOG.schedule(() -> {
                logger.warning("IPC write exceeded " + WRITE_TIMEOUT_MS + "ms; closing the connection");
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }, delay, TimeUnit.MILLISECONDS);
            return new Watchdog(task);
        }

        private void cancel() {
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    public void setFrameHandler(Consumer<Frame> handler) {
        this.frameHandler = handler == null ? frame -> {
        } : handler;
    }

    public void respond(long requestId, Opcode opcode, byte[] payload) {
        Frame frame = new Frame(opcode, requestId, payload);
        try {
            writer.execute(() -> {
                try {
                    writeFrame(frame);
                } catch (IOException e) {
                    logger.warning("Failed to send IPC response: " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.fine("IPC writer is closed; dropping " + opcode);
        }
    }

    public void nack(NackMessage nack) {
        respond(0L, Opcode.NACK, nack.encode());
    }

    private void ping() {
        if (state.get() != State.CONNECTED) {
            return;
        }
        request(Opcode.PING, new byte[0], 5000L).whenComplete((frame, error) -> {
            if (error != null) {
                logger.warning("IPC ping failed, reconnecting: " + error.getMessage());
                state.set(State.DISCONNECTED);
                closeSocket();
            }
        });
    }

    private static String readErrorMessage(byte[] payload) {
        try {
            return new ByteReader(payload).readUtf(512);
        } catch (ProtocolException e) {
            return "<unreadable>";
        }
    }

    private void failPending(Throwable cause) {
        for (Long requestId : pending.keySet()) {
            CompletableFuture<Frame> future = pending.remove(requestId);
            if (future != null) {
                future.completeExceptionally(cause);
            }
        }
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        in = null;
        out = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state.set(State.CLOSED);
        closeSocket();
        failPending(new IOException("IPC client closed"));
        timer.shutdownNow();
        writer.shutdown();
        try {
            writer.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // No shutdownNow here: interrupting a half-written frame makes the proxy treat the whole
        // connection as corrupt, and the writer threads are daemons anyway.
        io.shutdownNow();
    }
}
