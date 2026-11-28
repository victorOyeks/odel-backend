package com.odel.registration.service;

import java.time.LocalDate;

/**
 * The registration window of a semester on a given day: not set, not open yet (R-01), open, late (after the close, until the
 * late close: needs an approved request) or closed. Pure, so it is unit tested.
 */
public record Window(State state, LocalDate opensOn, LocalDate closesOn, LocalDate lateClosesOn, LocalDate changesCloseOn) {
    public enum State { NOT_SET, NOT_OPEN, OPEN, LATE, CLOSED }

    /** {@code changesCloseOn} null = the default: the day after the late close (or after the close when there is none). */
    public static Window of(LocalDate today, LocalDate opensOn, LocalDate closesOn, LocalDate lateClosesOn, LocalDate changesCloseOn) {
        if (opensOn == null || closesOn == null) return new Window(State.NOT_SET, opensOn, closesOn, lateClosesOn, changesCloseOn);
        LocalDate lastLate = lateClosesOn != null && lateClosesOn.isAfter(closesOn) ? lateClosesOn : null;
        LocalDate changes = changesCloseOn != null ? changesCloseOn : defaultChangesCloseOn(closesOn, lastLate);
        State s;
        if (today.isBefore(opensOn)) s = State.NOT_OPEN;
        else if (!today.isAfter(closesOn)) s = State.OPEN;
        else if (lastLate != null && !today.isAfter(lastLate)) s = State.LATE;
        else s = State.CLOSED;
        return new Window(s, opensOn, closesOn, lastLate, changes);
    }

    public static LocalDate defaultChangesCloseOn(LocalDate closesOn, LocalDate lateClosesOn) {
        if (closesOn == null) return null;
        return (lateClosesOn != null && lateClosesOn.isAfter(closesOn) ? lateClosesOn : closesOn).plusDays(1);
    }

    /** The last day anyone may register without a late request: the close. */
    public LocalDate lastRegistrationDay() {
        return closesOn;
    }

    public boolean changesOpen(LocalDate today) {
        return changesCloseOn != null && !today.isAfter(changesCloseOn);
    }

    /** "Opens Tue 2 Feb 2027", "Open until Tue 16 Feb 2027", "Late registration until Fri 26 Feb 2027", "Closed on …". */
    public String label() {
        return switch (state) {
            case NOT_SET -> "Not set up yet";
            case NOT_OPEN -> "Opens " + Dates.full(opensOn);
            case OPEN -> "Open until " + Dates.full(closesOn);
            case LATE -> "Late registration until " + Dates.full(lateClosesOn);
            case CLOSED -> "Closed on " + Dates.full(lateClosesOn != null ? lateClosesOn : closesOn);
        };
    }
}
