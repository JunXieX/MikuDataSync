package com.junxiex.mikudatasync.common;

import java.nio.charset.StandardCharsets;

/** UTF-8 helpers for fields that carry a byte limit on the wire. */
public final class Utf8 {

    private Utf8() {
    }

    /**
     * Truncates to at most {@code maxBytes} UTF-8 bytes without splitting a code point. Encoding a
     * longer string than the reader accepts would make the whole payload unreadable.
     */
    public static String clip(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
