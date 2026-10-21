package com.junxiex.mikudatasync.common;

import java.util.zip.CRC32C;

/** CRC32C helper used by snapshots and journal records. */
public final class Crc32c {

    private Crc32c() {
    }

    public static int compute(byte[] data, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }
}
