package com.odel.academic.service;

import java.util.Locale;

/** Shapes of codes and how they are written. */
public final class Codes {
    private Codes() {}

    /** Faculty, department and programme codes: letters, digits and hyphens. */
    public static final String UNIT = "^[A-Z0-9][A-Z0-9-]{0,29}$";
    public static final String CENTRE = "^[A-Z0-9]{2,6}$";
    private static final String COURSE = "^([A-Z]{2,4})\\s*(\\d{3}[A-Z]?)$";

    public static String unit(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    /** "csc201", "CSC  201" → "CSC 201"; null when it is not a course code. */
    public static String course(String code) {
        if (code == null) return null;
        var m = java.util.regex.Pattern.compile(COURSE).matcher(code.trim().toUpperCase(Locale.ROOT));
        return m.matches() ? m.group(1) + " " + m.group(2) : null;
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
