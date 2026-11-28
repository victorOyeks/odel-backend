package com.odel.common.security;

import java.util.Locale;
import java.util.Optional;

/**
 * How a university's staff prove who they are before the authenticator two-step (chosen per university by the provider).
 * Accounts are tied to the work email, not to the method, so a university can change method and keep its staff.
 * The provider console itself always uses Google.
 */
public enum StaffSignIn {
    GOOGLE("Google Workspace", "Google work account", "Google"),
    MICROSOFT("Microsoft 365", "Microsoft work account", "Microsoft"),
    EMAIL_CODE("Email code", "work email", "an emailed code");

    private final String label;
    private final String account;
    private final String firstFactor;

    StaffSignIn(String label, String account, String firstFactor) {
        this.label = label;
        this.account = account;
        this.firstFactor = firstFactor;
    }

    /** "Google Workspace", "Microsoft 365", "Email code": what the console shows. */
    public String label() { return label; }

    /** How the first step reads in an audit line: "Microsoft and a two-step code". */
    public String firstFactor() { return firstFactor; }

    /**
     * One sentence for invitations and staff emails, e.g. "Sign in with your Microsoft work account (your @wesleyuni.edu.ng address)".
     * No full stop, so a template can follow it with ":" and the link.
     */
    public String how(String domain) {
        String at = domain == null || domain.isBlank() ? "" : " (your @" + domain + " address)";
        return switch (this) {
            case GOOGLE, MICROSOFT -> "Sign in with your " + account + at;
            case EMAIL_CODE -> "Sign in with a code sent to your work email" + at;
        };
    }

    /** Reads GOOGLE / MICROSOFT / EMAIL_CODE (any case); empty when the value is not one of them. */
    public static Optional<StaffSignIn> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A stored or sent value, defaulting to Google (every university before 28 Nov 2026 used Google). */
    public static StaffSignIn orGoogle(String value) {
        return parse(value).orElse(GOOGLE);
    }
}
