package com.junxiex.mikudatasync.common;

import java.nio.charset.StandardCharsets;

/** Big-endian byte reader with varint support. */
public final class ByteReader {

    private final byte[] data;
    private int position;

    public ByteReader(byte[] data) {
        this.data = data;
    }

    public int remaining() {
        return data.length - position;
    }

    private void require(int bytes) throws ProtocolException {
        if (remaining() < bytes) {
            throw new ProtocolException("unexpected end of payload: need " + bytes + ", have " + remaining());
        }
    }

    public int readByte() throws ProtocolException {
        require(1);
        return data[position++] & 0xFF;
    }

    public boolean readBoolean() throws ProtocolException {
        return readByte() != 0;
    }

    public int readUnsignedShort() throws ProtocolException {
        require(2);
        int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        position += 2;
        return value;
    }

    public int readInt() throws ProtocolException {
        require(4);
        int value = ((data[position] & 0xFF) << 24)
                | ((data[position + 1] & 0xFF) << 16)
                | ((data[position + 2] & 0xFF) << 8)
                | (data[position + 3] & 0xFF);
        position += 4;
        return value;
    }

    public long readLong() throws ProtocolException {
        require(8);
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[position + i] & 0xFFL);
        }
        position += 8;
        return value;
    }

    public float readFloat() throws ProtocolException {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() throws ProtocolException {
        return Double.longBitsToDouble(readLong());
    }

    public int readVarInt() throws ProtocolException {
        int value = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            int current = readByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new ProtocolException("varint is too long");
    }

    public long readVarLong() throws ProtocolException {
        long value = 0L;
        for (int shift = 0; shift < 64; shift += 7) {
            int current = readByte();
            value |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new ProtocolException("varlong is too long");
    }

    public byte[] readBytes(int length) throws ProtocolException {
        if (length < 0) {
            throw new ProtocolException("negative length");
        }
        require(length);
        byte[] out = new byte[length];
        System.arraycopy(data, position, out, 0, length);
        position += length;
        return out;
    }

    public byte[] readLengthPrefixed(int maxLength) throws ProtocolException {
        int length = readVarInt();
        if (length < 0 || length > maxLength) {
            throw new ProtocolException("length out of range: " + length);
        }
        return readBytes(length);
    }

    public String readUtf(int maxBytes) throws ProtocolException {
        return new String(readLengthPrefixed(maxBytes), StandardCharsets.UTF_8);
    }
}
