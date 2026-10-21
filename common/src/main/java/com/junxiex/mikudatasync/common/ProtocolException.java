package com.junxiex.mikudatasync.common;

import java.io.IOException;

/** Raised when a frame, snapshot or payload does not match the protocol. */
public class ProtocolException extends IOException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
