package com.odel.registration.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Dates as people read them: "Tue 16 Feb 2027", "16 Feb", "2–16 Feb 2027". Calendar days are Africa/Lagos. */
public final class Dates {
    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
    private static final DateTimeFormatter PLAIN = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

    private Dates() {}

    /** "Tue 16 Feb 2027" */
    public static String full(LocalDate d) { return d == null ? "" : LONG.format(d); }

    /** "Tue 16 Feb" */
    public static String weekday(LocalDate d) { return d == null ? "" : DAY_MONTH.format(d); }

    /** "16 Feb" */
    public static String day(LocalDate d) { return d == null ? "" : SHORT.format(d); }

    /** "16 Feb 2027" */
    public static String plain(LocalDate d) { return d == null ? "" : PLAIN.format(d); }

    public static LocalDate lagos(Instant i) { return i == null ? null : i.atZone(LAGOS).toLocalDate(); }

    /** "2–16 Feb 2027", or "28 Jan – 3 Feb 2027" across months. */
    public static String range(LocalDate a, LocalDate b) {
        if (a == null || b == null) return "";
        if (a.getMonth() == b.getMonth() && a.getYear() == b.getYear()) return a.getDayOfMonth() + "–" + PLAIN.format(b);
        return SHORT.format(a) + " – " + PLAIN.format(b);
    }
}
