package com.junxiex.mikudatasync.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Length-prefixed frame codec: [length:int][opcode:u8][requestId:i64][payload]. */
public final class FrameCodec {

    private static final int HEADER_BYTES = 1 + 8;

    private FrameCodec() {
    }

    public static void write(OutputStream output, Frame frame) throws IOException {
        int length = HEADER_BYTES + frame.payload().length;
        if (length > Protocol.MAX_FRAME_BYTES) {
            throw new ProtocolException("frame too large: " + length);
        }
        DataOutputStream out = output instanceof DataOutputStream dataOut ? dataOut : new DataOutputStream(output);
        out.writeInt(length);
        out.writeByte(frame.opcode().code());
        out.writeLong(frame.requestId());
        out.write(frame.payload());
        out.flush();
    }

    public static Frame read(InputStream input, int maxFrameBytes) throws IOException {
        DataInputStream in = input instanceof DataInputStream dataIn ? dataIn : new DataInputStream(input);
        int length = in.readInt();
        if (length < HEADER_BYTES || length > maxFrameBytes) {
            throw new ProtocolException("invalid frame length: " + length);
        }
        int opcodeCode = in.readUnsignedByte();
        Opcode opcode = Opcode.fromCode(opcodeCode);
        if (opcode == null) {
            throw new ProtocolException("unknown opcode: " + opcodeCode);
        }
        long requestId = in.readLong();
        byte[] payload = new byte[length - HEADER_BYTES];
        in.readFully(payload);
        return new Frame(opcode, requestId, payload);
    }
}
