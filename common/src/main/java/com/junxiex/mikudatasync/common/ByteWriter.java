package com.junxiex.mikudatasync.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Growable big-endian byte writer with varint support. */
public final class ByteWriter {

    private byte[] buffer;
    private int size;

    public ByteWriter() {
        this(256);
    }

    public ByteWriter(int initialCapacity) {
        this.buffer = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        if (size + extra <= buffer.length) {
            return;
        }
        int capacity = buffer.length;
        while (capacity < size + extra) {
            capacity <<= 1;
        }
        buffer = Arrays.copyOf(buffer, capacity);
    }

    public ByteWriter writeByte(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
        return this;
    }

    public ByteWriter writeBoolean(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    public ByteWriter writeShort(int value) {
        ensure(2);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
        return this;
    }

    public ByteWriter writeInt(int value) {
        ensure(4);
        buffer[size++] = (byte) (value >>> 24);
        buffer[size++] = (byte) (value >>> 16);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
        return this;
    }

    public ByteWriter writeLong(long value) {
        ensure(8);
        for (int shift = 56; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
        return this;
    }

    public ByteWriter writeVarInt(int value) {
        int current = value;
        while ((current & ~0x7F) != 0) {
            writeByte((current & 0x7F) | 0x80);
            current >>>= 7;
        }
        return writeByte(current);
    }

    public ByteWriter writeVarLong(long value) {
        long current = value;
        while ((current & ~0x7FL) != 0L) {
            writeByte(((int) current & 0x7F) | 0x80);
            current >>>= 7;
        }
        return writeByte((int) current);
    }

    public ByteWriter writeBytes(byte[] bytes) {
        if (bytes.length > 0) {
            ensure(bytes.length);
            System.arraycopy(bytes, 0, buffer, size, bytes.length);
            size += bytes.length;
        }
        return this;
    }

    public ByteWriter writeLengthPrefixed(byte[] bytes) {
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    public ByteWriter writeUtf(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    public int size() {
        return size;
    }

    public void reset() {
        size = 0;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }
}
