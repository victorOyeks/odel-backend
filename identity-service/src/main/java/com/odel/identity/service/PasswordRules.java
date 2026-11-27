package com.odel.identity.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Students' password rules (M-04, M-11): at least 8 characters with a letter and a number. Shown to the student as they type. */
public final class PasswordRules {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;
    public static final String HINT = "At least 8 characters, with a letter and a number.";

    public record Rule(String key, String label) {}

    public static final List<Rule> RULES = List.of(
            new Rule("length", "At least 8 characters"),
            new Rule("letter", "At least one letter"),
            new Rule("number", "At least one number"));

    private PasswordRules() {}

    /** Field errors for the screen (empty when the password is fine). */
    public static Map<String, String> check(String password, String confirm) {
        Map<String, String> f = new LinkedHashMap<>();
        String p = password == null ? "" : password;
        if (p.isEmpty()) f.put("password", "Enter a password.");
        else if (p.length() < MIN_LENGTH) f.put("password", "Use at least 8 characters.");
        else if (p.length() > MAX_LENGTH) f.put("password", "Use at most 128 characters.");
        else if (p.chars().noneMatch(Character::isLetter)) f.put("password", "Add at least one letter.");
        else if (p.chars().noneMatch(Character::isDigit)) f.put("password", "Add at least one number.");
        else if (p.isBlank()) f.put("password", "A password cannot be only spaces.");
        if (f.isEmpty() && (confirm == null || !confirm.equals(p))) f.put("confirmPassword", "The two passwords do not match.");
        return f;
    }
}
