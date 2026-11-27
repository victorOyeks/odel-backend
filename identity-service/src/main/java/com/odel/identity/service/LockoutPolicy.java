package com.odel.identity.service;

import java.time.Duration;
import java.time.Instant;

/** Five wrong passwords pause sign-in for 15 minutes (E-05). The count starts again after a pause or a good sign-in. */
public final class LockoutPolicy {
    public static final int MAX_WRONG = 5;
    public static final Duration PAUSE = Duration.ofMinutes(15);

    private LockoutPolicy() {}

    public record State(int failed, Instant lockedUntil) {
        public boolean locked(Instant now) { return lockedUntil != null && now.isBefore(lockedUntil); }

        public long retryInSeconds(Instant now) { return locked(now) ? Math.max(1, Duration.between(now, lockedUntil).toSeconds()) : 0; }

        public int attemptsLeft() { return Math.max(0, MAX_WRONG - failed); }
    }

    /** The state to work from now: a pause that has ended clears the count. */
    public static State current(int failed, Instant lockedUntil, Instant now) {
        if (lockedUntil != null && !now.isBefore(lockedUntil)) return new State(0, null);
        return new State(failed, lockedUntil);
    }

    /** After one more wrong password. */
    public static State afterWrong(State s, Instant now) {
        int failed = s.failed() + 1;
        return new State(failed, failed >= MAX_WRONG ? now.plus(PAUSE) : null);
    }
}
