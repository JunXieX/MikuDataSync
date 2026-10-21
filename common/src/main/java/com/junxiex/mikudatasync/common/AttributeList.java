package com.junxiex.mikudatasync.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Attribute base values (ATTRIBUTES segment). Modifiers are deliberately not serialized in M2:
 * equipment and effects recreate them, resending them would double the bonus.
 */
public final class AttributeList {

    public record Entry(String attributeKey, double baseValue) {
    }

    private AttributeList() {
    }

    public static byte[] encode(List<Entry> entries) {
        ByteWriter writer = new ByteWriter(64 + entries.size() * 24);
        writer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            writer.writeUtf(entry.attributeKey());
            writer.writeDouble(entry.baseValue());
        }
        return writer.toByteArray();
    }

    public static List<Entry> decode(byte[] payload) throws ProtocolException {
        ByteReader reader = new ByteReader(payload);
        int count = reader.readVarInt();
        if (count < 0 || count > 256) {
            throw new ProtocolException("invalid attribute count: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(reader.readUtf(128), reader.readDouble()));
        }
        if (reader.remaining() != 0) {
            throw new ProtocolException("unexpected trailing bytes in attribute list");
        }
        return entries;
    }
}
