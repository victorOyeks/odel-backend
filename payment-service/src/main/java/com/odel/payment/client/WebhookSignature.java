package com.odel.payment.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Paystack's webhook signature: hex HMAC-SHA512 of the raw body with the secret key. Compared in constant time. */
public final class WebhookSignature {
    private WebhookSignature() {}

    public static String sign(byte[] body, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean valid(byte[] body, String signature, String key) {
        if (signature == null || signature.isBlank() || key == null || key.isBlank() || body == null) return false;
        byte[] expected = sign(body, key).getBytes(StandardCharsets.US_ASCII);
        byte[] given = signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }
}
