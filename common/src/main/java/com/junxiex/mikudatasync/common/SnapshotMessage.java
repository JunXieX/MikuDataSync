package com.junxiex.mikudatasync.common;

import java.nio.charset.StandardCharsets;

/**
 * IPC-level snapshot transfer object.
 *
 * <pre>
 * seq:varlong | captureSeq:varlong | saveCause:utf | sourceServer:utf | envelope:lenPrefixed
 * </pre>
 */
public record SnapshotMessage(
        long seq,
        long captureSeq,
        String saveCause,
        String sourceServer,
        byte[] envelope) {

    public byte[] encode() {
        ByteWriter writer = new ByteWriter(256 + envelope.length);
        encodeTo(writer);
        return writer.toByteArray();
    }

    /** Writes into an existing buffer: the envelope is copied once, not once per wrapping layer. */
    public void encodeTo(ByteWriter writer) {
        String cause = saveCause == null ? "" : saveCause;
        String server = sourceServer == null ? "" : sourceServer;
        if (cause.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_SAVE_CAUSE_BYTES) {
            throw new IllegalArgumentException("saveCause is too long: " + cause);
        }
        if (server.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_SERVER_ID_BYTES) {
            throw new IllegalArgumentException("sourceServer is too long: " + server);
        }
        writer.writeVarLong(seq);
        writer.writeVarLong(captureSeq);
        writer.writeUtf(cause);
        writer.writeUtf(server);
        writer.writeLengthPrefixed(envelope);
    }

    public static SnapshotMessage decode(byte[] payload) throws ProtocolException {
        return decode(new ByteReader(payload));
    }

    /** Reads from the current position and requires the reader to be exhausted afterwards. */
    public static SnapshotMessage decode(ByteReader reader) throws ProtocolException {
        long seq = reader.readVarLong();
        long captureSeq = reader.readVarLong();
        String saveCause = reader.readUtf(Protocol.MAX_SAVE_CAUSE_BYTES);
        String sourceServer = reader.readUtf(Protocol.MAX_SERVER_ID_BYTES);
        byte[] envelope = reader.readLengthPrefixed(Protocol.MAX_SNAPSHOT_BYTES);
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in snapshot message");
        }
        return new SnapshotMessage(seq, captureSeq, saveCause, sourceServer, envelope);
    }
}
