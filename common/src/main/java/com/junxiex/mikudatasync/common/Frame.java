package com.junxiex.mikudatasync.common;

/** One IPC message. */
public record Frame(Opcode opcode, long requestId, byte[] payload) {
}
