package com.junxiex.mikudatasync.common;

/** IPC message types. */
public enum Opcode {

    HELLO(1),
    HELLO_ACK(2),
    ERROR(3),

    GET(10),
    SNAPSHOT(11),
    PUT(12),
    PUT_OK(13),
    FLUSH_REQ(14),
    HANDOFF_ABORT(15),
    ACK(16),
    NACK(17),

    PING(20),
    PONG(21),
    STATS_REQ(22),
    STATS_RES(23);

    private final int code;

    Opcode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Opcode fromCode(int code) {
        for (Opcode opcode : values()) {
            if (opcode.code == code) {
                return opcode;
            }
        }
        return null;
    }
}
