package com.junxiex.mikudatasync.common;

/** Scalar player state that is cheap to copy and apply (CORE segment). */
public record CoreData(
        double health,
        double absorption,
        int food,
        float saturation,
        float exhaustion,
        int expLevel,
        float expProgress,
        int totalExp,
        String gameMode,
        boolean allowFlight,
        boolean flying,
        float flySpeed,
        float walkSpeed,
        int heldSlot,
        int fireTicks,
        int freezeTicks,
        int remainingAir,
        float fallDistance
) {

    public byte[] encode() {
        ByteWriter writer = new ByteWriter(128);
        writer.writeDouble(health);
        writer.writeDouble(absorption);
        writer.writeVarInt(food);
        writer.writeFloat(saturation);
        writer.writeFloat(exhaustion);
        writer.writeVarInt(expLevel);
        writer.writeFloat(expProgress);
        writer.writeVarInt(totalExp);
        writer.writeUtf(gameMode);
        writer.writeBoolean(allowFlight);
        writer.writeBoolean(flying);
        writer.writeFloat(flySpeed);
        writer.writeFloat(walkSpeed);
        writer.writeVarInt(heldSlot);
        writer.writeVarInt(fireTicks);
        writer.writeVarInt(freezeTicks);
        writer.writeVarInt(remainingAir);
        writer.writeFloat(fallDistance);
        return writer.toByteArray();
    }

    public static CoreData decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        CoreData data = new CoreData(
                reader.readDouble(),
                reader.readDouble(),
                reader.readVarInt(),
                reader.readFloat(),
                reader.readFloat(),
                reader.readVarInt(),
                reader.readFloat(),
                reader.readVarInt(),
                reader.readUtf(32),
                reader.readBoolean(),
                reader.readBoolean(),
                reader.readFloat(),
                reader.readFloat(),
                reader.readVarInt(),
                reader.readVarInt(),
                reader.readVarInt(),
                reader.readVarInt(),
                reader.readFloat()
        );
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in core data");
        }
        return data;
    }
}
