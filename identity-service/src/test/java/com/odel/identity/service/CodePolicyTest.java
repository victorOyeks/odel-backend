package com.odel.identity.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodePolicyTest {
    final Instant now = Instant.parse("2026-11-27T10:00:00Z");

    @Test
    void firstCodeGoesAtOnce() {
        var d = CodePolicy.canSend(List.of(), now);
        assertThat(d.allowed()).isTrue();
        assertThat(d.sendsLeftAfter()).isEqualTo(4);
    }

    @Test
    void resendWaitsSixtySeconds() {
        var d = CodePolicy.canSend(List.of(now.minusSeconds(18)), now);
        assertThat(d.verdict()).isEqualTo(CodePolicy.SendVerdict.TOO_SOON);
        assertThat(d.retryInSeconds()).isEqualTo(42);
        assertThat(CodePolicy.canSend(List.of(now.minusSeconds(60)), now).allowed()).isTrue();
    }

    @Test
    void atMostFiveAnHour() {
        List<Instant> five = List.of(now.minusSeconds(3000), now.minusSeconds(2400), now.minusSeconds(1800), now.minusSeconds(1200), now.minusSeconds(600));
        var d = CodePolicy.canSend(five, now);
        assertThat(d.verdict()).isEqualTo(CodePolicy.SendVerdict.HOURLY_LIMIT);
        assertThat(d.retryInSeconds()).isEqualTo(600); // the oldest leaves the hour at 10:50 + ... = 600 s from now
        // Sends older than an hour no longer count.
        List<Instant> old = List.of(now.minusSeconds(4000), now.minusSeconds(3900), now.minusSeconds(3800), now.minusSeconds(3700), now.minusSeconds(600));
        assertThat(CodePolicy.canSend(old, now).allowed()).isTrue();
    }

    @Test
    void codeExpiresAfterFifteenMinutes() {
        var stored = new CodePolicy.Stored("h", now, now.plus(CodePolicy.VALID_FOR), 0);
        assertThat(CodePolicy.check(stored, "h", now.plusSeconds(899)).verdict()).isEqualTo(CodePolicy.CheckVerdict.OK);
        assertThat(CodePolicy.check(stored, "h", now.plusSeconds(900)).verdict()).isEqualTo(CodePolicy.CheckVerdict.EXPIRED);
        assertThat(CodePolicy.check(null, "h", now).verdict()).isEqualTo(CodePolicy.CheckVerdict.EXPIRED);
    }

    @Test
    void threeWrongTriesUseTheCodeUp() {
        var fresh = new CodePolicy.Stored("h", now, now.plusSeconds(900), 0);
        assertThat(CodePolicy.check(fresh, "x", now)).isEqualTo(new CodePolicy.Check(CodePolicy.CheckVerdict.WRONG, 2));
        var once = new CodePolicy.Stored("h", now, now.plusSeconds(900), 1);
        assertThat(CodePolicy.check(once, "x", now)).isEqualTo(new CodePolicy.Check(CodePolicy.CheckVerdict.WRONG, 1));
        var twice = new CodePolicy.Stored("h", now, now.plusSeconds(900), 2);
        assertThat(CodePolicy.check(twice, "x", now).verdict()).isEqualTo(CodePolicy.CheckVerdict.USED_UP);
        var thrice = new CodePolicy.Stored("h", now, now.plusSeconds(900), 3);
        assertThat(CodePolicy.check(thrice, "h", now).verdict()).isEqualTo(CodePolicy.CheckVerdict.USED_UP);
    }

    @Test
    void masksTheEmail() {
        assertThat(CodePolicy.mask("adeola.okafor@gmail.com")).isEqualTo("a•••••@gmail.com");
        assertThat(CodePolicy.mask(null)).isNull();
    }
}
