package com.junxiex.mikudatasync.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC-SHA256 helpers for the backend/proxy handshake. */
public final class HmacAuth {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacAuth() {
    }

    public static byte[] sign(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public static String signHex(String secret, String message) {
        return HexFormat.of().formatHex(sign(secret, message));
    }

    public static boolean verifyHex(String secret, String message, String expectedHex) {
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(expectedHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, message), expected);
    }
}
