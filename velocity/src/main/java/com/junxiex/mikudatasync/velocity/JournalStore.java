package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /** Upper bound for the exponential compaction retry backoff. */
    private static final long MAX_COMPACT_BACKOFF_MS = 60L * 60L * 1000L;

    private final Path path;
    private final VelocityConfig.FsyncMode fsyncMode;
    private final long compactionThresholdBytes;
    private final Logger logger;
    private final Map<UUID, Long> latestOffsets = new HashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final ScheduledExecutorService fsyncExecutor;

    /** Records appended while a compaction copy is running; replayed onto the new file. */
    private final List<PendingRecord> pendingDuringCompaction = new ArrayList<>();

    private FileChannel channel;
    private long validLength;
    private boolean closed;
    private boolean broken;
    private boolean compacting;
    private long nextCompactionAt;
    private long compactionBackoffMs = 60_000L;

    public JournalStore(Path path, VelocityConfig.FsyncMode fsyncMode, int fsyncIntervalMs,
                        long compactionThresholdBytes, Logger logger) throws IOException {
        this.path = path;
        this.fsyncMode = fsyncMode;
        this.compactionThresholdBytes = compactionThresholdBytes;
        this.logger = logger;
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
        if (broken) {
            throw new IOException("journal is broken: compaction could not reopen " + path);
        }
        byte[] payload = message.encode();
        byte[] header = encodeHeader(message.seq(), uuid, payload);
        byte[] record = new byte[header.length + payload.length];
        System.arraycopy(header, 0, record, 0, header.length);
        System.arraycopy(payload, 0, record, header.length, payload.length);

        long offset = validLength;
        writeAt(record, offset);
        if (fsyncMode == VelocityConfig.FsyncMode.ALWAYS || force) {
            try {
                channel.force(false);
            } catch (IOException e) {
                // Roll back the bookkeeping so memory and disk agree; the periodic fsync retries.
                truncateQuietly(offset);
                validLength = offset;
                dirty.set(true);
                throw e;
            }
        } else {
            dirty.set(true);
        }
        validLength = offset + record.length;
        latestOffsets.put(uuid, offset);
        if (compacting) {
            // This record lives only in the file being replaced; replay it onto the new file.
            pendingDuringCompaction.add(new PendingRecord(uuid, record));
        }
    }

    /** Returns the stored snapshot, or null when this player has no record. */
    public synchronized SnapshotMessage get(UUID uuid) throws IOException {
        Long offset = latestOffsets.get(uuid);
        if (offset == null) {
            return null;
        }
        SnapshotMessage message = readAt(channel, offset);
        if (message == null) {
            throw new IOException("journal record for " + uuid + " at " + offset + " is unreadable");
        }
        return message;
    }

    /** Highest sequence number persisted for this player, 0 when unknown. */
    public synchronized long latestSeq(UUID uuid) {
        Long offset = latestOffsets.get(uuid);
        if (offset == null) {
            return 0L;
        }
        try {
            byte[] header = new byte[HEADER_BYTES];
            if (readFully(channel, ByteBuffer.wrap(header), offset) < HEADER_BYTES) {
                return 0L;
            }
            return ByteBuffer.wrap(header, 4 + 1 + 1, 8).getLong();
        } catch (IOException e) {
            return 0L;
        }
    }

    public synchronized long size() {
        return validLength;
    }

    public synchronized int trackedPlayers() {
        return latestOffsets.size();
    }

    public synchronized boolean isBroken() {
        return broken;
    }

    public synchronized void forceIfDirty() {
        if (!dirty.compareAndSet(true, false) || closed || broken) {
            return;
        }
        try {
            channel.force(false);
        } catch (IOException e) {
            dirty.set(true);
            logger.warn("Journal fsync failed for {}", path, e);
        }
    }

    public void maybeCompact() {
        long threshold = compactionThresholdBytes;
        synchronized (this) {
            if (closed || broken || compacting || validLength < threshold) {
                return;
            }
            if (System.currentTimeMillis() < nextCompactionAt) {
                return;
            }
        }
        compact();
    }

    /**
     * Rewrites the journal with only the latest record per player. The copy is built from a separate
     * read-only channel and without holding the journal monitor, so a compaction never blocks the
     * switch gate; records appended meanwhile are replayed onto the new file before it goes live.
     */
    private void compact() {
        Map<UUID, Long> offsets;
        synchronized (this) {
            if (closed || broken || compacting || validLength < compactionThresholdBytes) {
                return;
            }
            if (System.currentTimeMillis() < nextCompactionAt) {
                return;
            }
            offsets = new HashMap<>(latestOffsets);
            compacting = true;
            pendingDuringCompaction.clear();
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        boolean success = false;
        try {
            long written = 0L;
            // Offsets change when the file is rewritten, so the new positions must be recorded while
            // copying; reusing the old map pointed every lookup at the wrong record.
            Map<UUID, Long> newOffsets = new HashMap<>(offsets.size() * 2);
            try (FileChannel source = FileChannel.open(path, StandardOpenOption.READ);
                 FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Map.Entry<UUID, Long> entry : offsets.entrySet()) {
                    byte[] record = readRecord(source, entry.getValue(), entry.getKey());
                    newOffsets.put(entry.getKey(), written);
                    written += writeFully(out, record, written);
                }
                out.force(true);
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                channel.close();
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                // Opened right after the move: if this fails the pending replay below never runs, so
                // the failure must be treated as a broken journal instead of silently dropping records.
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                latestOffsets.clear();
                latestOffsets.putAll(newOffsets);
                validLength = written;
                try {
                    replayPendingDuringCompaction();
                } catch (IOException e) {
                    // Half-written replay leaves offsets pointing at records that do not exist, which
                    // would make every later read fail. Refuse further writes instead.
                    broken = true;
                    logger.error("Journal {} is unusable after a failed compaction replay; snapshots are "
                            + "no longer persisted", path, e);
                    throw e;
                }
                compacting = false;
                // Pending records were just written into the new file and only exist in the page
                // cache, so the dirty flag must stay set until the force below actually succeeds.
                dirty.set(true);
                try {
                    channel.force(false);
                    dirty.set(false);
                } catch (IOException e) {
                    dirty.set(true);
                    logger.warn("Journal force after compaction failed for {}; the periodic fsync retries", path, e);
                }
            }
            success = true;
            compactionBackoffMs = 60_000L;
            // If the live set alone is still above the threshold the rewrite cannot shrink the file;
            // retrying every cycle would rewrite the whole journal forever, so back off instead.
            nextCompactionAt = written >= compactionThresholdBytes
                    ? System.currentTimeMillis() + compactionBackoffMs
                    : 0L;
            logger.info("Journal compacted: {} players, {} bytes", newOffsets.size(), written);
        } catch (IOException e) {
            // Keep serving from the old journal and back off: retrying every cycle on a full disk or
            // a locked file would burn IO forever.
            compactionBackoffMs = Math.min(compactionBackoffMs * 2L, MAX_COMPACT_BACKOFF_MS);
            nextCompactionAt = System.currentTimeMillis() + compactionBackoffMs;
            logger.error("Journal compaction failed for {}; retrying in {} ms", path, compactionBackoffMs, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            synchronized (this) {
                // The file may already have been replaced by the temp copy; records appended during
                // the compaction exist only in pendingDuringCompaction and must be replayed here too.
                if (!channel.isOpen()) {
                    try {
                        channel = FileChannel.open(path, StandardOpenOption.CREATE,
                                StandardOpenOption.READ, StandardOpenOption.WRITE);
                        replay();
                        replayPendingDuringCompaction();
                    } catch (IOException reopenFailure) {
                        broken = true;
                        logger.error("Journal {} could not be reopened after a failed compaction; "
                                + "snapshots are no longer persisted", path, reopenFailure);
                    }
                }
            }
        } finally {
            if (!success) {
                synchronized (this) {
                    compacting = false;
                    pendingDuringCompaction.clear();
                }
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    logger.debug("Could not delete leftover compaction file {}", temp);
                }
            }
        }
    }

    /** Re-appends records that were written while a compaction copy was in progress. */
    private void replayPendingDuringCompaction() throws IOException {
        for (PendingRecord pending : pendingDuringCompaction) {
            long offset = validLength;
            writeAt(pending.record(), offset);
            validLength = offset + pending.record().length;
            latestOffsets.put(pending.uuid(), offset);
        }
        pendingDuringCompaction.clear();
        dirty.set(true);
    }

    private void replay() throws IOException {
        latestOffsets.clear();
        long size = channel.size();
        long position = 0L;
        long dropped = 0L;
        while (position + HEADER_BYTES <= size) {
            byte[] header = new byte[HEADER_BYTES];
            if (readFully(channel, ByteBuffer.wrap(header), position) < HEADER_BYTES) {
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
            if (readFully(channel, ByteBuffer.wrap(payload), position + HEADER_BYTES) < payloadLength) {
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
            dropped = size - position;
            channel.truncate(position);
        }
        channel.position(position);
        if (dropped > 0) {
            logger.warn("Journal {} had {} trailing bytes that failed validation; truncated", path, dropped);
        }
    }

    private SnapshotMessage readAt(FileChannel source, long offset) throws IOException {
        byte[] record = readRecordOrNull(source, offset);
        if (record == null) {
            return null;
        }
        return SnapshotMessage.decode(java.util.Arrays.copyOfRange(record, HEADER_BYTES, record.length));
    }

    private byte[] readRecord(FileChannel source, long offset, UUID uuid) throws IOException {
        byte[] record = readRecordOrNull(source, offset);
        if (record == null) {
            throw new IOException("journal record for " + uuid + " at " + offset + " is unreadable");
        }
        return record;
    }

    private byte[] readRecordOrNull(FileChannel source, long offset) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        if (readFully(source, ByteBuffer.wrap(header), offset) < HEADER_BYTES) {
            return null;
        }
        ByteBuffer h = ByteBuffer.wrap(header);
        int magic = h.getInt();
        byte version = h.get();
        byte type = h.get();
        h.getLong();
        h.getLong();
        h.getLong();
        int payloadLength = h.getInt();
        int expectedCrc = h.getInt();
        if (magic != MAGIC || version != RECORD_VERSION || type != TYPE_SNAPSHOT
                || payloadLength < 0 || payloadLength > Protocol.MAX_FRAME_BYTES) {
            return null;
        }
        byte[] payload = new byte[payloadLength];
        if (readFully(source, ByteBuffer.wrap(payload), offset + HEADER_BYTES) < payloadLength) {
            return null;
        }
        if (crc(header, payload) != expectedCrc) {
            return null;
        }
        byte[] record = new byte[HEADER_BYTES + payloadLength];
        System.arraycopy(header, 0, record, 0, HEADER_BYTES);
        System.arraycopy(payload, 0, record, HEADER_BYTES, payloadLength);
        return record;
    }

    private void writeAt(byte[] record, long offset) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(record);
        long written = 0L;
        while (buffer.hasRemaining()) {
            int count = channel.write(buffer, offset + written);
            if (count <= 0) {
                throw new IOException("journal write made no progress at " + (offset + written));
            }
            written += count;
        }
    }

    private void truncateQuietly(long length) {
        try {
            channel.truncate(length);
        } catch (IOException ignored) {
        }
    }

    private static long writeFully(FileChannel out, byte[] record, long position) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(record);
        long written = 0L;
        while (buffer.hasRemaining()) {
            int count = out.write(buffer, position + written);
            if (count <= 0) {
                throw new IOException("journal compaction made no progress at " + (position + written));
            }
            written += count;
        }
        return written;
    }

    private static int readFully(FileChannel source, ByteBuffer buffer, long position) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = source.read(buffer, position + total);
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
    public synchronized void close() {
        closed = true;
        fsyncExecutor.shutdownNow();
        // force() before the isOpen check: an interrupted channel (shutdownNow elsewhere) is already
        // closed, and skipping the force would silently drop the last switch/quit snapshot. A closed
        // channel cannot be forced, so that case is reported as a real data-loss risk.
        try {
            channel.force(false);
        } catch (java.nio.channels.ClosedChannelException e) {
            logger.error("Journal {} was closed before its final fsync; the last committed snapshot may "
                    + "be lost", path);
        } catch (IOException e) {
            logger.warn("Final journal fsync failed for {}", path, e);
        }
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
    }

    private record PendingRecord(UUID uuid, byte[] record) {
    }
}
