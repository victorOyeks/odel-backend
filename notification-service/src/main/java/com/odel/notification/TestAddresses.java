package com.odel.notification;

import java.util.Locale;
import java.util.Set;

/** Addresses on reserved test domains can never receive mail, so they are never handed to Brevo (as in hospio-backend). */
public final class TestAddresses {
    private static final Set<String> TLDS = Set.of("test", "example", "invalid", "localhost", "local");
    private static final Set<String> DOMAINS = Set.of("example.com", "example.org", "example.net");

    private TestAddresses() {}

    public static boolean isTest(String email) {
        if (email == null || !email.contains("@")) return true;
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT).trim();
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        if (TLDS.contains(tld)) return true;
        return DOMAINS.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    }
}
