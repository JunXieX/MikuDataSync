package com.junxiex.mikudatasync.common;

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
        writer.writeVarLong(seq);
        writer.writeVarLong(captureSeq);
        writer.writeUtf(saveCause);
        writer.writeUtf(sourceServer);
        writer.writeLengthPrefixed(envelope);
        return writer.toByteArray();
    }

    public static SnapshotMessage decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        long seq = reader.readVarLong();
        long captureSeq = reader.readVarLong();
        String saveCause = reader.readUtf(64);
        String sourceServer = reader.readUtf(128);
        byte[] envelope = reader.readLengthPrefixed(Protocol.MAX_SNAPSHOT_BYTES);
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in snapshot message");
        }
        return new SnapshotMessage(seq, captureSeq, saveCause, sourceServer, envelope);
    }
}
