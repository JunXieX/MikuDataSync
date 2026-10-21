package com.junxiex.mikudatasync.common;

import java.util.ArrayList;
import java.util.List;

/** Potion effect list (EFFECTS segment). */
public final class EffectList {

    public record Entry(String typeKey, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
    }

    private EffectList() {
    }

    public static byte[] encode(List<Entry> entries) {
        ByteWriter writer = new ByteWriter(64 + entries.size() * 16);
        writer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            writer.writeUtf(entry.typeKey());
            writer.writeVarInt(entry.duration());
            writer.writeVarInt(entry.amplifier());
            int flags = 0;
            if (entry.ambient()) {
                flags |= 1;
            }
            if (entry.particles()) {
                flags |= 2;
            }
            if (entry.icon()) {
                flags |= 4;
            }
            writer.writeByte(flags);
        }
        return writer.toByteArray();
    }

    public static List<Entry> decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        int count = reader.readVarInt();
        if (count < 0 || count > 256) {
            throw new ProtocolException("invalid effect count: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = reader.readUtf(128);
            int duration = reader.readVarInt();
            int amplifier = reader.readVarInt();
            int flags = reader.readByte();
            entries.add(new Entry(key, duration, amplifier, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0));
        }
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in effect list");
        }
        return entries;
    }
}
