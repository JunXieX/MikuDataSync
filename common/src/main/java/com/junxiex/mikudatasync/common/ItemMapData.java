package com.junxiex.mikudatasync.common;

/** Persisted locked-map payload stored inside the item itself (HuskSync-style). */
public record ItemMapData(String originServer, int sourceMapId, byte scale,
                          int centerX, int centerZ, boolean locked, byte[] colors) {

    public byte[] encode() {
        ByteWriter writer = new ByteWriter(64 + colors.length);
        writer.writeUtf(originServer);
        writer.writeInt(sourceMapId);
        writer.writeByte(scale);
        writer.writeInt(centerX);
        writer.writeInt(centerZ);
        writer.writeByte(locked ? 1 : 0);
        writer.writeBytes(colors);
        return writer.toByteArray();
    }

    public static ItemMapData decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        String origin = reader.readUtf(128);
        int id = reader.readInt();
        byte scale = (byte) reader.readByte();
        int centerX = reader.readInt();
        int centerZ = reader.readInt();
        boolean locked = reader.readByte() != 0;
        byte[] colors = reader.readBytes(MapDataList.COLORS_LENGTH);
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing item map bytes");
        }
        return new ItemMapData(origin, id, scale, centerX, centerZ, locked, colors);
    }
}
