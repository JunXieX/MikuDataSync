package com.junxiex.mikudatasync.common;

import java.util.ArrayList;
import java.util.List;

/** Map colour data referenced by filled-map items. Colours are 128x128 palette bytes. */
public final class MapDataList {

    public static final int MAP_WIDTH = 128;
    public static final int MAP_HEIGHT = 128;
    public static final int COLORS_LENGTH = MAP_WIDTH * MAP_HEIGHT;
    public static final int MAX_MAPS = 24;

    public record Entry(int mapId, byte scale, int centerX, int centerZ, boolean locked, byte[] colors) {
    }

    private MapDataList() {
    }

    public static byte[] encode(List<Entry> entries) {
        ByteWriter writer = new ByteWriter(2 + entries.size() * (18 + COLORS_LENGTH));
        writer.writeShort(entries.size());
        for (Entry entry : entries) {
            if (entry.colors() == null || entry.colors().length != COLORS_LENGTH) {
                throw new IllegalArgumentException("invalid map colors for id " + entry.mapId());
            }
            writer.writeInt(entry.mapId());
            writer.writeByte(entry.scale());
            writer.writeInt(entry.centerX());
            writer.writeInt(entry.centerZ());
            writer.writeByte(entry.locked() ? 1 : 0);
            writer.writeBytes(entry.colors());
        }
        return writer.toByteArray();
    }

    public static List<Entry> decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        int count = reader.readUnsignedShort();
        if (count > MAX_MAPS) {
            throw new ProtocolException("too many maps: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = reader.readInt();
            byte scale = (byte) reader.readByte();
            int centerX = reader.readInt();
            int centerZ = reader.readInt();
            boolean locked = reader.readByte() != 0;
            byte[] colors = reader.readBytes(COLORS_LENGTH);
            entries.add(new Entry(id, scale, centerX, centerZ, locked, colors));
        }
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing map bytes: " + reader.remaining());
        }
        return entries;
    }
}
