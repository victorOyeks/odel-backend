package com.odel.identity;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/** RFC 6238 time-based codes (6 digits, 30 seconds, SHA-1): what Google Authenticator and similar apps use. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;

    private Totp() {}

    public static String newSecret(SecureRandom random) {
        byte[] b = new byte[20];
        random.nextBytes(b);
        return base32(b);
    }

    public static long step(Instant now) {
        return now.getEpochSecond() / STEP_SECONDS;
    }

    public static String code(String base32Secret, long step) {
        return code(base32Decode(base32Secret), step, 6, "HmacSHA1");
    }

    static String code(byte[] key, long step, int digits, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
            int mod = (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", binary % mod);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Accepts the current step and one either side (clock drift). Returns the step that matched. */
    public static OptionalLong verify(String base32Secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) return OptionalLong.empty();
        long current = step(now);
        for (long s = current - 1; s <= current + 1; s++) {
            if (java.security.MessageDigest.isEqual(code(base32Secret, s).getBytes(), code.getBytes())) return OptionalLong.of(s);
        }
        return OptionalLong.empty();
    }

    public static String group(String secret) {
        return secret.replaceAll("(.{4})(?!$)", "$1 ");
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.replace(" ", "").replace("=", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Not base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}
