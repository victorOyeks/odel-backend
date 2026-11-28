package com.odel.student.service;

import com.odel.student.entity.ProfileRequest;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Checks and wording for the profile page and corrections (B3). Pure, unit-tested. */
public final class ProfileRules {
    private ProfileRules() {}

    public static final List<String> STATES = List.of("Abia", "Adamawa", "Akwa Ibom", "Anambra", "Bauchi", "Bayelsa", "Benue", "Borno",
            "Cross River", "Delta", "Ebonyi", "Edo", "Ekiti", "Enugu", "FCT", "Gombe", "Imo", "Jigawa", "Kaduna", "Kano", "Katsina", "Kebbi",
            "Kogi", "Kwara", "Lagos", "Nasarawa", "Niger", "Ogun", "Ondo", "Osun", "Oyo", "Plateau", "Rivers", "Sokoto", "Taraba", "Yobe", "Zamfara");

    public static final String PHONE_HINT = "Enter a Nigerian mobile number, like 0803 412 4417.";

    /** "Lagos", "lagos state", "Abuja" → the canonical name; null when not a state. */
    public static String state(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("(?i)\\s+state$", "").trim();
        if (s.equalsIgnoreCase("Abuja") || s.equalsIgnoreCase("Federal Capital Territory")) return "FCT";
        for (String st : STATES) if (st.equalsIgnoreCase(s)) return st;
        return null;
    }

    /** "0803 412 4417" → "0803 *** 4417" (as L-14 shows it). */
    public static String maskPhone(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.length() < 8) return phone;
        return d.substring(0, 4) + " *** " + d.substring(d.length() - 4);
    }

    /** "adeola.okafor@gmail.com" → "adeola.o•••@gmail.com". */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return null;
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String keep = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, Math.min(8, local.length() - 1));
        return keep + "•••" + email.substring(at);
    }

    /** A cleaned name ("okafor" stays as typed apart from spaces), or null with the problem in {@code problem[0]}. */
    public static String name(String raw, int max, boolean mayBeEmpty, String[] problem) {
        String v = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) {
            if (mayBeEmpty) return "";
            problem[0] = "Enter the name as it should be.";
            return null;
        }
        if (v.length() > max) { problem[0] = "Use at most " + max + " characters."; return null; }
        if (!v.matches("^[\\p{L}][\\p{L} '\\-.]*$")) { problem[0] = "Use letters only (spaces, hyphens and apostrophes are fine)."; return null; }
        return v;
    }

    /** A date of birth for someone aged 14 to 100 on {@code today}. */
    public static LocalDate dateOfBirth(String raw, LocalDate today, String[] problem) {
        LocalDate d;
        try {
            d = LocalDate.parse(raw == null ? "" : raw.trim());
        } catch (DateTimeParseException e) {
            problem[0] = "Enter the date as on your birth certificate (day, month and year).";
            return null;
        }
        if (d.isAfter(today.minusYears(14)) || d.isBefore(today.minusYears(100))) {
            problem[0] = "Check the year: that date would make you " + Math.max(0, java.time.Period.between(d, today).getYears()) + ".";
            return null;
        }
        return d;
    }

    /** "M", "male" → "M"; "F", "female" → "F"; otherwise null. */
    public static String gender(String raw) {
        if (raw == null) return null;
        String g = raw.trim().toUpperCase(Locale.ROOT);
        if (g.equals("M") || g.equals("MALE")) return "M";
        if (g.equals("F") || g.equals("FEMALE")) return "F";
        return null;
    }

    public static String genderLabel(String g) {
        return g == null ? null : g.equals("M") ? "Male" : g.equals("F") ? "Female" : g;
    }

    /** One line for the history and emails: "14 Oyemekun Road, Akure, Ondo". */
    public static String addressLine(String line, String town, String state) {
        StringJoiner j = new StringJoiner(", ");
        for (String p : new String[]{line, town, state}) if (p != null && !p.isBlank()) j.add(p);
        return j.length() == 0 ? null : j.toString();
    }

    /** "Mrs Chioma Okafor (Mother) · 0805 111 2233". */
    public static String nextOfKinLine(String name, String relationship, String phone) {
        if (name == null || name.isBlank()) return null;
        return name + (relationship == null || relationship.isBlank() ? "" : " (" + relationship + ")") + (phone == null ? "" : " · " + phone);
    }

    public static String fieldLabel(String field) {
        return switch (field) {
            case "PHONE" -> "Phone";
            case "EMAIL" -> "Email";
            case "ADDRESS" -> "Home address";
            case "NEXT_OF_KIN" -> "Next of kin";
            case "PHOTO" -> "Photo";
            case "MATRIC_NUMBER" -> "Matric number";
            default -> {
                try {
                    yield ProfileRequest.Field.valueOf(field).label().replace(" spelling", "");
                } catch (IllegalArgumentException e) {
                    yield field;
                }
            }
        };
    }

    /** "412 KB", "1.8 MB". */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
