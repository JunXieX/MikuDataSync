package com.junxiex.mikudatasync.common;

import java.util.UUID;

/** NACK payload: UUID + appliedSeq + machine-readable code + human message. */
public record NackMessage(UUID uuid, long appliedSeq, String code, String message) {

    public byte[] encode() {
        ByteWriter writer = new ByteWriter(64);
        writer.writeLong(uuid.getMostSignificantBits());
        writer.writeLong(uuid.getLeastSignificantBits());
        writer.writeVarLong(appliedSeq);
        writer.writeUtf(code);
        writer.writeUtf(message);
        return writer.toByteArray();
    }

    public static NackMessage decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        UUID uuid = new UUID(reader.readLong(), reader.readLong());
        long appliedSeq = reader.readVarLong();
        String code = reader.readUtf(64);
        String message = reader.readUtf(512);
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in nack message");
        }
        return new NackMessage(uuid, appliedSeq, code, message);
    }
}
