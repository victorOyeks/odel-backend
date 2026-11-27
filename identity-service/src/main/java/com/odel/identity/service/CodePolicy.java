package com.odel.identity.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Emailed 6-digit codes (activation and password reset): valid 15 minutes, re-sent after 60 seconds, at most 5 an hour
 * per student, 3 wrong tries per code. Pure rules, so the timings are unit-tested without Redis.
 */
public final class CodePolicy {
    public static final Duration VALID_FOR = Duration.ofMinutes(15);
    public static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    public static final Duration WINDOW = Duration.ofHours(1);
    public static final int MAX_SENDS_PER_HOUR = 5;
    public static final int MAX_TRIES = 3;

    private CodePolicy() {}

    public enum SendVerdict { OK, TOO_SOON, HOURLY_LIMIT }

    public record SendDecision(SendVerdict verdict, long retryInSeconds, int sendsLeftAfter) {
        public boolean allowed() { return verdict == SendVerdict.OK; }
    }

    /** May another code be sent now, given when the earlier ones went (any order, any age)? */
    public static SendDecision canSend(List<Instant> sentAt, Instant now) {
        List<Instant> recent = sentAt.stream().filter(t -> t.isAfter(now.minus(WINDOW))).sorted().toList();
        if (!recent.isEmpty()) {
            Instant last = recent.get(recent.size() - 1);
            long since = Duration.between(last, now).toSeconds();
            if (since < RESEND_AFTER.toSeconds()) {
                return new SendDecision(SendVerdict.TOO_SOON, RESEND_AFTER.toSeconds() - since, MAX_SENDS_PER_HOUR - recent.size());
            }
        }
        if (recent.size() >= MAX_SENDS_PER_HOUR) {
            Instant oldest = recent.get(0);
            long wait = Math.max(1, Duration.between(now, oldest.plus(WINDOW)).toSeconds());
            return new SendDecision(SendVerdict.HOURLY_LIMIT, wait, 0);
        }
        return new SendDecision(SendVerdict.OK, 0, MAX_SENDS_PER_HOUR - recent.size() - 1);
    }

    /** A code as kept (hashed) while it is live. */
    public record Stored(String hash, Instant sentAt, Instant expiresAt, int wrongTries) {}

    public enum CheckVerdict { OK, WRONG, USED_UP, EXPIRED }

    public record Check(CheckVerdict verdict, int attemptsLeft) {}

    /** Checks a typed code's hash against the live one. A third wrong try uses the code up. */
    public static Check check(Stored stored, String typedHash, Instant now) {
        if (stored == null || !now.isBefore(stored.expiresAt())) return new Check(CheckVerdict.EXPIRED, 0);
        if (stored.wrongTries() >= MAX_TRIES) return new Check(CheckVerdict.USED_UP, 0);
        if (java.security.MessageDigest.isEqual(stored.hash().getBytes(), typedHash.getBytes())) return new Check(CheckVerdict.OK, MAX_TRIES - stored.wrongTries());
        int left = MAX_TRIES - stored.wrongTries() - 1;
        return new Check(left <= 0 ? CheckVerdict.USED_UP : CheckVerdict.WRONG, Math.max(0, left));
    }

    /** "a•••••@gmail.com": enough for the student to recognise the address, not enough to learn it. */
    public static String mask(String email) {
        if (email == null || !email.contains("@")) return null;
        int at = email.indexOf('@');
        return email.charAt(0) + "•••••" + email.substring(at);
    }
}
