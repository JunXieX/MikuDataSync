package com.junxiex.mikudatasync.common;

import java.util.ArrayList;
import java.util.List;

/** Advancements segment: only advancements with at least one awarded criterion are transferred. */
public final class AdvancementList {

    public record Entry(String advancementKey, List<String> criteria) {
    }

    private AdvancementList() {
    }

    public static byte[] encode(List<Entry> entries) {
        ByteWriter writer = new ByteWriter(64 + entries.size() * 32);
        writer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            writer.writeUtf(entry.advancementKey());
            writer.writeVarInt(entry.criteria().size());
            for (String criterion : entry.criteria()) {
                writer.writeUtf(criterion);
            }
        }
        return writer.toByteArray();
    }

    public static List<Entry> decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        int count = reader.readVarInt();
        if (count < 0 || count > 100_000) {
            throw new ProtocolException("invalid advancement count: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = reader.readUtf(256);
            int criteriaCount = reader.readVarInt();
            if (criteriaCount < 0 || criteriaCount > 256) {
                throw new ProtocolException("invalid criteria count: " + criteriaCount);
            }
            List<String> criteria = new ArrayList<>(criteriaCount);
            for (int c = 0; c < criteriaCount; c++) {
                criteria.add(reader.readUtf(256));
            }
            entries.add(new Entry(key, criteria));
        }
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in advancement list");
        }
        return entries;
    }
}
