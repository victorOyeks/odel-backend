package com.odel.payment.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {
    static final String KEY = "sk_test_not_a_real_key";
    static final byte[] BODY = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"WU-PF-882134\",\"amount\":1500000}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void knownHmacSha512() {
        // HMAC-SHA512("key", "The quick brown fox jumps over the lazy dog"), the standard test vector.
        assertThat(WebhookSignature.sign("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8), "key"))
                .isEqualTo("b42af09057bac1e2d41708e48a902e09b5ff7f12ab428a4fe86653c73dd248fb82f948a549f7b791a5b41915ee4d1ec3935357e4e2317250d0372afa2ebeeb3a");
    }

    @Test
    void acceptsTheRightSignatureOnly() {
        String sig = WebhookSignature.sign(BODY, KEY);
        assertThat(WebhookSignature.valid(BODY, sig, KEY)).isTrue();
        assertThat(WebhookSignature.valid(BODY, sig.toUpperCase(), KEY)).as("hex case does not matter").isTrue();
        assertThat(WebhookSignature.valid(BODY, sig, "another key")).isFalse();
        byte[] changed = new String(BODY, StandardCharsets.UTF_8).replace("1500000", "150000").getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSignature.valid(changed, sig, KEY)).as("a changed body").isFalse();
        assertThat(WebhookSignature.valid(BODY, null, KEY)).isFalse();
        assertThat(WebhookSignature.valid(BODY, "", KEY)).isFalse();
        assertThat(WebhookSignature.valid(BODY, sig, "")).as("no key, nothing passes").isFalse();
    }
}
