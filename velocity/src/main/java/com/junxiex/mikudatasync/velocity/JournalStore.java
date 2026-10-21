package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.Crc32c;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.SnapshotMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Append-only snapshot journal. Record layout:
 * magic:i32 | version:u8 | type:u8 | seq:i64 | uuid:16 | payloadLen:i32 | crc32c:i32 | payload.
 * The tail is truncated on replay if a record fails its CRC check.
 */
public final class JournalStore implements AutoCloseable {

    private static final int MAGIC = 0x4D44534A; // "MDSJ"
    private static final int RECORD_VERSION = 1;
    private static final byte TYPE_SNAPSHOT = 1;
    private static final int HEADER_WITHOUT_CRC = 4 + 1 + 1 + 8 + 16 + 4;
    private static final int HEADER_BYTES = HEADER_WITHOUT_CRC + 4;

    private final Path path;
    private final VelocityConfig.FsyncMode fsyncMode;
    private final long compactionThresholdBytes;
    private final Object compactionLock = new Object();
    private final Map<UUID, Long> latestOffsets = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final ScheduledExecutorService fsyncExecutor;

    private FileChannel channel;
    private long validLength;
    private boolean closed;

    public JournalStore(Path path, VelocityConfig.FsyncMode fsyncMode, int fsyncIntervalMs,
                        long compactionThresholdBytes) throws IOException {
        this.path = path;
        this.fsyncMode = fsyncMode;
        this.compactionThresholdBytes = compactionThresholdBytes;
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        replay();

        this.fsyncExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mds-journal-fsync");
            thread.setDaemon(true);
            return thread;
        });
        if (fsyncMode == VelocityConfig.FsyncMode.INTERVAL) {
            int interval = Math.max(10, fsyncIntervalMs);
            fsyncExecutor.scheduleWithFixedDelay(this::forceIfDirty, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void append(UUID uuid, SnapshotMessage message, boolean force) throws IOException {
        if (closed) {
            throw new IOException("journal is closed");
        }
        byte[] payload = message.encode();
        byte[] header = encodeHeader(message.seq(), uuid, payload);

        ByteBuffer buffer = ByteBuffer.allocate(header.length + payload.length);
        buffer.put(header).put(payload);
        buffer.flip();

        long offset = validLength;
        long written = 0L;
        while (buffer.hasRemaining()) {
            written += channel.write(buffer, offset + written);
        }
        validLength = offset + header.length + payload.length;
        latestOffsets.put(uuid, offset);
        if (fsyncMode == VelocityConfig.FsyncMode.ALWAYS || force) {
            channel.force(false);
            dirty.set(false);
        } else {
            dirty.set(true);
        }
    }

    public SnapshotMessage get(UUID uuid) throws IOException {
        Long offset = latestOffsets.get(uuid);
        if (offset == null) {
            return null;
        }
        return readAt(offset);
    }

    public long size() {
        return validLength;
    }

    public int trackedPlayers() {
        return latestOffsets.size();
    }

    public void forceIfDirty() {
        if (!dirty.compareAndSet(true, false) || closed) {
            return;
        }
        try {
            channel.force(false);
        } catch (IOException ignored) {
            dirty.set(true);
        }
    }

    public void maybeCompact() {
        if (validLength < compactionThresholdBytes) {
            return;
        }
        synchronized (compactionLock) {
            if (validLength < compactionThresholdBytes || closed) {
                return;
            }
            compact();
        }
    }

    private void compact() {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (FileChannel out = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Map.Entry<UUID, Long> entry : latestOffsets.entrySet()) {
                    SnapshotMessage message = readAt(entry.getValue());
                    if (message == null) {
                        continue;
                    }
                    byte[] payload = message.encode();
                    byte[] header = encodeHeader(message.seq(), entry.getKey(), payload);
                    ByteBuffer buffer = ByteBuffer.allocate(header.length + payload.length);
                    buffer.put(header).put(payload);
                    buffer.flip();
                    long position = 0L;
                    while (buffer.hasRemaining()) {
                        position += out.write(buffer, position);
                    }
                }
                out.force(true);
            }
            channel.close();
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            replay();
        } catch (IOException e) {
            // Keep serving from the old journal; compaction is retried on the next threshold hit.
            try {
                if (!channel.isOpen()) {
                    channel = FileChannel.open(path, StandardOpenOption.CREATE,
                            StandardOpenOption.READ, StandardOpenOption.WRITE);
                    replay();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void replay() throws IOException {
        latestOffsets.clear();
        long size = channel.size();
        long position = 0L;
        while (position + HEADER_BYTES <= size) {
            byte[] header = new byte[HEADER_BYTES];
            if (readFully(ByteBuffer.wrap(header), position) < HEADER_BYTES) {
                break;
            }
            ByteBuffer h = ByteBuffer.wrap(header);
            int magic = h.getInt();
            byte version = h.get();
            byte type = h.get();
            long seq = h.getLong();
            long uuidMost = h.getLong();
            long uuidLeast = h.getLong();
            int payloadLength = h.getInt();
            int expectedCrc = h.getInt();
            if (magic != MAGIC || version != RECORD_VERSION || type != TYPE_SNAPSHOT
                    || payloadLength < 0 || payloadLength > Protocol.MAX_FRAME_BYTES
                    || position + HEADER_BYTES + payloadLength > size) {
                break;
            }
            byte[] payload = new byte[payloadLength];
            if (readFully(ByteBuffer.wrap(payload), position + HEADER_BYTES) < payloadLength) {
                break;
            }
            if (crc(header, payload) != expectedCrc) {
                break;
            }
            latestOffsets.put(new UUID(uuidMost, uuidLeast), position);
            position += HEADER_BYTES + payloadLength;
        }
        validLength = position;
        if (size > position) {
            channel.truncate(position);
        }
        channel.position(position);
    }

    private SnapshotMessage readAt(long offset) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        if (readFully(ByteBuffer.wrap(header), offset) < HEADER_BYTES) {
            return null;
        }
        ByteBuffer h = ByteBuffer.wrap(header);
        int magic = h.getInt();
        byte version = h.get();
        byte type = h.get();
        long seq = h.getLong();
        h.getLong();
        h.getLong();
        int payloadLength = h.getInt();
        int expectedCrc = h.getInt();
        if (magic != MAGIC || version != RECORD_VERSION || type != TYPE_SNAPSHOT
                || payloadLength < 0 || payloadLength > Protocol.MAX_FRAME_BYTES) {
            return null;
        }
        byte[] payload = new byte[payloadLength];
        if (readFully(ByteBuffer.wrap(payload), offset + HEADER_BYTES) < payloadLength) {
            return null;
        }
        if (crc(header, payload) != expectedCrc) {
            return null;
        }
        return SnapshotMessage.decode(payload);
    }

    private int readFully(ByteBuffer buffer, long position) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static byte[] encodeHeader(long seq, UUID uuid, byte[] payload) {
        ByteWriter writer = new ByteWriter(HEADER_BYTES);
        writer.writeInt(MAGIC);
        writer.writeByte(RECORD_VERSION);
        writer.writeByte(TYPE_SNAPSHOT);
        writer.writeLong(seq);
        writer.writeLong(uuid.getMostSignificantBits());
        writer.writeLong(uuid.getLeastSignificantBits());
        writer.writeInt(payload.length);
        byte[] withoutCrc = writer.toByteArray();
        ByteWriter full = new ByteWriter(HEADER_BYTES);
        full.writeBytes(withoutCrc);
        full.writeInt(crc(withoutCrc, payload));
        return full.toByteArray();
    }

    private static int crc(byte[] headerWithoutCrc, byte[] payload) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(headerWithoutCrc, 0, Math.min(HEADER_WITHOUT_CRC, headerWithoutCrc.length));
        crc.update(payload);
        return (int) crc.getValue();
    }

    @Override
    public void close() {
        closed = true;
        fsyncExecutor.shutdownNow();
        try {
            if (channel.isOpen()) {
                channel.force(false);
                channel.close();
            }
        } catch (IOException ignored) {
        }
    }
}
