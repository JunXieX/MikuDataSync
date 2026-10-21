package com.junxiex.mikudatasync.common;

/** Raised when a configuration file is invalid. */
public class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
