package com.odel.payment.service;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Our references: "WU-PF-882134" (university initials, platform fee, six digits) and refunds "RF-33124". */
public final class References {
    private References() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SMALL = Set.of("OF", "THE", "AND", "FOR", "AT");

    /** "Wesley University" → WU; "Federal University of Technology, Akure" → FUTA (at most four letters). */
    public static String prefix(String universityName) {
        if (universityName == null || universityName.isBlank()) return "OD";
        // "Wesley University (wes-pay-1x)": what is in brackets is not part of the name.
        String p = Arrays.stream(universityName.replaceAll("\\(.*?\\)", " ").toUpperCase(Locale.ROOT).split("[^A-Z0-9]+"))
                .filter(w -> !w.isBlank() && !SMALL.contains(w)).map(w -> w.substring(0, 1)).collect(Collectors.joining());
        if (p.isEmpty()) return "OD";
        return p.length() > 4 ? p.substring(0, 4) : p;
    }

    public static String payment(String universityName) {
        return prefix(universityName) + "-PF-" + (100_000 + RANDOM.nextInt(900_000));
    }

    public static String refund() {
        return "RF-" + (10_000 + RANDOM.nextInt(90_000));
    }
}
