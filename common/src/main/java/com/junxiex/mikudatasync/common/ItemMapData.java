package com.junxiex.mikudatasync.common;

import java.util.ArrayList;
import java.util.List;

/** Persisted locked-map payload stored inside the item itself. */
public record ItemMapData(String originServer, int sourceMapId, byte scale,
                          int centerX, int centerZ, boolean locked, byte[] colors,
                          List<CursorData> cursors) {

    public record CursorData(byte x, byte y, byte direction, String type, String caption) {
    }

    public ItemMapData(String originServer, int sourceMapId, byte scale,
                       int centerX, int centerZ, boolean locked, byte[] colors) {
        this(originServer, sourceMapId, scale, centerX, centerZ, locked, colors, List.of());
    }

    public byte[] encode() {
        ByteWriter writer = new ByteWriter(96 + colors.length);
        writer.writeUtf(originServer);
        writer.writeInt(sourceMapId);
        writer.writeByte(scale);
        writer.writeInt(centerX);
        writer.writeInt(centerZ);
        writer.writeByte(locked ? 1 : 0);
        writer.writeBytes(colors);
        writer.writeVarInt(cursors.size());
        for (CursorData cursor : cursors) {
            writer.writeByte(cursor.x());
            writer.writeByte(cursor.y());
            writer.writeByte(cursor.direction());
            writer.writeUtf(cursor.type());
            writer.writeUtf(cursor.caption() == null ? "" : cursor.caption());
        }
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
        List<CursorData> cursors = new ArrayList<>();
        if (reader.remaining() > 0) {
            int count = reader.readVarInt();
            if (count < 0 || count > 512) {
                throw new ProtocolException("invalid map cursor count: " + count);
            }
            for (int i = 0; i < count; i++) {
                byte x = (byte) reader.readByte();
                byte y = (byte) reader.readByte();
                byte direction = (byte) reader.readByte();
                String type = reader.readUtf(64);
                String caption = reader.readUtf(256);
                cursors.add(new CursorData(x, y, direction, type, caption.isEmpty() ? null : caption));
            }
        }
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing item map bytes");
        }
        return new ItemMapData(origin, id, scale, centerX, centerZ, locked, colors, List.copyOf(cursors));
    }
}
