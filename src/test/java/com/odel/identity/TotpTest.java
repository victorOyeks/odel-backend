package com.odel.identity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {
    @Test
    void matchesRfc6238Vectors() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.code(key, 59 / 30, 8, "HmacSHA1")).isEqualTo("94287082");
        assertThat(Totp.code(key, 1111111109L / 30, 8, "HmacSHA1")).isEqualTo("07081804");
        assertThat(Totp.code(key, 20000000000L / 30, 8, "HmacSHA1")).isEqualTo("65353130");
    }

    @Test
    void base32RoundTripAndWindow() {
        String secret = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertThat(secret).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.step(now);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 1), now)).isPresent();
        assertThat(Totp.verify(secret, Totp.code(secret, step + 2), now)).isEmpty();
        assertThat(Totp.verify(secret, "12ab56", now)).isEmpty();
        assertThat(Totp.group("JBSWY3DPEHPK3PXP")).isEqualTo("JBSW Y3DP EHPK 3PXP");
    }
}
