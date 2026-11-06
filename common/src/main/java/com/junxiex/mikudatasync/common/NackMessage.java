package com.junxiex.mikudatasync.common;

import java.util.UUID;

/** NACK payload: UUID + appliedSeq + machine-readable code + human message. */
public record NackMessage(UUID uuid, long appliedSeq, String code, String message) {

    public byte[] encode() {
        ByteWriter writer = new ByteWriter(64);
        writer.writeLong(uuid.getMostSignificantBits());
        writer.writeLong(uuid.getLeastSignificantBits());
        writer.writeVarLong(appliedSeq);
        // Clipped to the reader's limits: an over-long exception message used to make the NACK
        // undecodable, which closed the whole IPC connection and failed every other player's switch.
        writer.writeUtf(Utf8.clip(code == null ? "" : code, 64));
        writer.writeUtf(Utf8.clip(message == null ? "" : message, 512));
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
