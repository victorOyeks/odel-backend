package com.odel.content.service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Plain-English labels: dates as "Mon 15 Feb", sizes as "1.2 MB", lengths as "18 min". */
public final class Labels {
    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.UK);

    private Labels() {}

    public static String day(LocalDate d) {
        return d == null ? null : DAY.format(d);
    }

    public static String shortDay(LocalDate d) {
        return d == null ? null : SHORT.format(d);
    }

    /** "Tue 9 Feb · 9:12 am". */
    public static String at(Instant t) {
        if (t == null) return null;
        ZonedDateTime z = t.atZone(LAGOS);
        return DAY.format(z) + " · " + TIME.format(z).toLowerCase(Locale.UK);
    }

    /** "24 Jan · 16 days" (how long ago), "today", "yesterday". */
    public static String ago(Instant t, LocalDate today) {
        if (t == null) return "Never";
        LocalDate d = t.atZone(LAGOS).toLocalDate();
        long days = ChronoUnit.DAYS.between(d, today);
        if (days <= 0) return "Today";
        if (days == 1) return "Yesterday";
        return SHORT.format(d) + " · " + days + " days";
    }

    public static String size(Long bytes) {
        if (bytes == null) return null;
        double mb = bytes / 1048576.0;
        if (bytes < 1024) return bytes + " bytes";
        if (mb < 1) return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        if (mb < 10) return String.format(Locale.UK, "%.1f MB", mb);
        if (mb < 1024) return Math.round(mb) + " MB";
        return String.format(Locale.UK, "%.1f GB", mb / 1024);
    }

    /** "about 2.3 MB a minute". */
    public static String perMinute(long bytes, int seconds) {
        if (seconds <= 0) return null;
        double mbMin = bytes / 1048576.0 / (seconds / 60.0);
        return mbMin < 1 ? String.format(Locale.UK, "about %.1f MB a minute", mbMin) : String.format(Locale.UK, "about %.1f MB a minute", mbMin);
    }

    /** "18 min", "1 h 05 min", "45 s". */
    public static String length(Integer seconds) {
        if (seconds == null || seconds <= 0) return null;
        if (seconds < 60) return seconds + " s";
        int min = (int) Math.round(seconds / 60.0);
        if (min < 60) return min + " min";
        return min / 60 + " h " + String.format("%02d", min % 60) + " min";
    }

    /** "7:02". */
    public static String clock(int seconds) {
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    public static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        StringBuilder sb = new StringBuilder();
        for (String p : name.trim().split("\\s+")) {
            String w = p.replace(".", "");
            if (w.matches("(?i)dr|mr|mrs|ms|miss|prof|professor|engr|rev|chief|sir")) continue;
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0)));
        }
        String s = sb.toString();
        return s.isEmpty() ? "?" : s.length() > 2 ? "" + s.charAt(0) + s.charAt(s.length() - 1) : s;
    }

    /** "lecture-06_final" → "Lecture 06 final". */
    public static String titleFromFile(String fileName) {
        String base = fileName == null ? "Untitled" : fileName.replaceAll("^.*[/\\\\]", "");
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[-_]+", " ").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) return "Untitled";
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }
}
