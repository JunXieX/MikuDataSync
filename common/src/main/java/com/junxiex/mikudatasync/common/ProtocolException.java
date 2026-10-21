package com.junxiex.mikudatasync.common;

/** Raised when a frame, snapshot or payload does not match the protocol. */
public class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
