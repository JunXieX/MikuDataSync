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
        int causeBytes = saveCause == null ? 0 : saveCause.getBytes(StandardCharsets.UTF_8).length;
        if (causeBytes > Protocol.MAX_SAVE_CAUSE_BYTES) {
            throw new IllegalArgumentException("saveCause is too long: " + causeBytes + " bytes");
        }
        int serverBytes = sourceServer == null ? 0 : sourceServer.getBytes(StandardCharsets.UTF_8).length;
        if (serverBytes > Protocol.MAX_SERVER_ID_BYTES) {
            throw new IllegalArgumentException("sourceServer is too long: " + serverBytes + " bytes");
        }
        ByteWriter writer = new ByteWriter(256 + envelope.length);
        writer.writeVarLong(seq);
        writer.writeVarLong(captureSeq);
        writer.writeUtf(saveCause == null ? "" : saveCause);
        writer.writeUtf(sourceServer == null ? "" : sourceServer);
        writer.writeLengthPrefixed(envelope);
        return writer.toByteArray();
    }

    public static SnapshotMessage decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
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
