package com.junxiex.mikudatasync.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Statistics segment: only non-zero entries are transferred.
 * statTypeKey is a registry key such as "minecraft:item_used"; valueKey is the material/entity/custom key.
 */
public final class StatisticsList {

    public record Entry(String statTypeKey, String valueKey, int value) {
    }

    private StatisticsList() {
    }

    public static byte[] encode(List<Entry> entries) {
        ByteWriter writer = new ByteWriter(64 + entries.size() * 24);
        writer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            writer.writeUtf(entry.statTypeKey());
            writer.writeUtf(entry.valueKey() == null ? "" : entry.valueKey());
            writer.writeVarInt(entry.value());
        }
        return writer.toByteArray();
    }

    public static List<Entry> decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        int count = reader.readVarInt();
        if (count < 0 || count > 1_000_000) {
            throw new ProtocolException("invalid statistic count: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String typeKey = reader.readUtf(256);
            String valueKey = reader.readUtf(256);
            int value = reader.readVarInt();
            entries.add(new Entry(typeKey, valueKey.isEmpty() ? null : valueKey, value));
        }
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in statistics list");
        }
        return entries;
    }
}
