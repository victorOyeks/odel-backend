package com.odel.identity.service;

import java.util.Locale;

/** Staff work emails and the university's staff email domain (pure rules, unit-tested). */
public final class StaffEmailRules {
    private static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private StaffEmailRules() {}

    /** Trimmed and lower-cased; null stays null. */
    public static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean looksLikeEmail(String email) {
        return email != null && email.trim().matches(EMAIL);
    }

    /**
     * True when the address ends with exactly "@" + domain (any case). A subdomain (x@mail.wesleyuni.edu.ng) or a lookalike
     * (x@wesleyuni.edu.ng.evil.com, x@notwesleyuni.edu.ng) is not on the domain. No domain set (the provider console) = any.
     */
    public static boolean onDomain(String email, String domain) {
        if (domain == null || domain.isBlank()) return true;
        if (!looksLikeEmail(email)) return false;
        String e = normalise(email);
        return e.substring(e.lastIndexOf('@') + 1).equals(domain.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", ""));
    }
}
