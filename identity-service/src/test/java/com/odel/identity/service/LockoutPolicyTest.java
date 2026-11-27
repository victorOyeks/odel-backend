package com.odel.identity.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LockoutPolicyTest {
    final Instant now = Instant.parse("2026-11-27T10:00:00Z");

    @Test
    void fifthWrongPasswordPausesSignInForFifteenMinutes() {
        var s = LockoutPolicy.current(0, null, now);
        for (int i = 1; i <= 4; i++) {
            s = LockoutPolicy.afterWrong(s, now);
            assertThat(s.locked(now)).isFalse();
            assertThat(s.attemptsLeft()).isEqualTo(5 - i);
        }
        s = LockoutPolicy.afterWrong(s, now);
        assertThat(s.locked(now)).isTrue();
        assertThat(s.retryInSeconds(now)).isEqualTo(900);
        assertThat(s.locked(now.plusSeconds(899))).isTrue();
        assertThat(s.retryInSeconds(now.plusSeconds(600))).isEqualTo(300);
    }

    @Test
    void theCountStartsAgainAfterThePause() {
        var after = LockoutPolicy.current(5, now.plusSeconds(900), now.plusSeconds(900));
        assertThat(after.locked(now.plusSeconds(900))).isFalse();
        assertThat(after.failed()).isZero();
        assertThat(after.attemptsLeft()).isEqualTo(5);
        var during = LockoutPolicy.current(5, now.plusSeconds(900), now.plusSeconds(10));
        assertThat(during.locked(now.plusSeconds(10))).isTrue();
    }
}
