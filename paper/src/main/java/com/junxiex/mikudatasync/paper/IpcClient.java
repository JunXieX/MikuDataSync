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
    private final ScheduledExecutorService timer;
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<Frame>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();

    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private volatile Consumer<Frame> frameHandler = frame -> {
    };

    public IpcClient(MikuDataSyncPaper plugin, PaperConfig config) {
        this.logger = plugin.getLogger();
        this.config = config;
        this.io = Executors.newSingleThreadExecutor(daemonFactory("mds-ipc-io"));
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

    public void ack(UUID uuid, long appliedSeq) {
        try {
            writeFrame(new Frame(Opcode.ACK, 0L, IpcPayloads.ack(uuid, appliedSeq)));
        } catch (IOException e) {
            logger.fine("Failed to send ACK: " + e.getMessage());
        }
    }

    private void runLoop() {
        long backoffMs = 1000L;
        while (!closed.get()) {
            try {
                connectAndServe();
                backoffMs = 1000L;
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
        if (ack.opcode() != Opcode.HELLO_ACK) {
            throw new IOException("unexpected handshake response: " + ack.opcode());
        }
        newSocket.setSoTimeout(0);
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
            if (frame.opcode() == Opcode.NACK) {
                try {
                    NackMessage nack = NackMessage.decode(frame.payload());
                    future.completeExceptionally(new IOException("backend NACK: " + nack.code() + " " + nack.message()));
                } catch (ProtocolException e) {
                    future.completeExceptionally(e);
                }
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

    private CompletableFuture<Frame> request(Opcode opcode, byte[] payload, long timeoutMs) {
        if (state.get() != State.CONNECTED) {
            CompletableFuture<Frame> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("IPC is not connected"));
            return failed;
        }
        long requestId = requestIds.incrementAndGet();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            writeFrame(new Frame(opcode, requestId, payload));
        } catch (IOException e) {
            pending.remove(requestId);
            future.completeExceptionally(e);
            return future;
        }
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((frame, error) -> pending.remove(requestId));
    }

    private void writeFrame(Frame frame) throws IOException {
        synchronized (writeLock) {
            DataOutputStream current = out;
            if (current == null || state.get() != State.CONNECTED) {
                throw new IOException("IPC is not connected");
            }
            FrameCodec.write(current, frame);
        }
    }

    public void setFrameHandler(Consumer<Frame> handler) {
        this.frameHandler = handler == null ? frame -> {
        } : handler;
    }

    public void respond(long requestId, Opcode opcode, byte[] payload) {
        try {
            writeFrame(new Frame(opcode, requestId, payload));
        } catch (IOException e) {
            logger.warning("Failed to send IPC response: " + e.getMessage());
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
        io.shutdownNow();
    }
}
