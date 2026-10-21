package com.junxiex.mikudatasync.common;

import java.util.UUID;

/** Small payload helpers shared by both sides. */
public final class IpcPayloads {

    private IpcPayloads() {
    }

    public static byte[] uuid(UUID uuid) {
        return new ByteWriter(16)
                .writeLong(uuid.getMostSignificantBits())
                .writeLong(uuid.getLeastSignificantBits())
                .toByteArray();
    }

    public static UUID readUuid(ByteReader reader) throws ProtocolException {
        long most = reader.readLong();
        long least = reader.readLong();
        return new UUID(most, least);
    }

    public static UUID parseUuid(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        UUID uuid = readUuid(reader);
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in uuid payload");
        }
        return uuid;
    }

    public static byte[] getRequest(UUID uuid) {
        return uuid(uuid);
    }

    public static byte[] ack(UUID uuid, long appliedSeq) {
        return new ByteWriter(24)
                .writeLong(uuid.getMostSignificantBits())
                .writeLong(uuid.getLeastSignificantBits())
                .writeVarLong(appliedSeq)
                .toByteArray();
    }

    public static long readAck(byte[] payload, UUID[] uuidOut) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        uuidOut[0] = readUuid(reader);
        long appliedSeq = reader.readVarLong();
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in ack payload");
        }
        return appliedSeq;
    }

    public static byte[] putOk(long seq) {
        return new ByteWriter(8).writeVarLong(seq).toByteArray();
    }

    public static long readPutOk(byte[] payload) throws ProtocolException {
        return new ByteReader(payload).readVarLong();
    }

    public static byte[] snapshotFound(SnapshotMessage message) {
        ByteWriter writer = new ByteWriter(256 + message.envelope().length);
        writer.writeBoolean(true);
        writer.writeBytes(message.encode());
        return writer.toByteArray();
    }

    public static byte[] snapshotMissing() {
        return new byte[] {0};
    }

    /** Returns null when the proxy has no snapshot for this player. */
    public static SnapshotMessage readSnapshotResponse(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        boolean found = reader.readBoolean();
        if (!found) {
            return null;
        }
        return SnapshotMessage.decode(reader.readBytes(reader.remaining()));
    }

    public static byte[] putRequest(UUID uuid, SnapshotMessage message) {
        ByteWriter writer = new ByteWriter(32 + message.envelope().length);
        writer.writeLong(uuid.getMostSignificantBits());
        writer.writeLong(uuid.getLeastSignificantBits());
        writer.writeBytes(message.encode());
        return writer.toByteArray();
    }

    public static SnapshotMessage readPutRequest(byte[] payload, UUID[] uuidOut) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        uuidOut[0] = readUuid(reader);
        return SnapshotMessage.decode(reader.readBytes(reader.remaining()));
    }
}
