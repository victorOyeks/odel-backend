package com.odel.academic.service;

import com.odel.academic.dto.SessionDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.academic.client.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Checks for a session and its two semesters (W-11b, W-11c). Pure, unit-tested. */
public final class SessionDates {
    private SessionDates() {}

    public static final String NAME = "^(\\d{4})/(\\d{4})$";
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US);

    /** Field → message. `complete` asks for everything a session needs before it can be made active. */
    public static Map<String, String> check(String name, List<SemesterIn> semesters, boolean complete) {
        Map<String, String> f = new LinkedHashMap<>();
        if (name == null || !name.trim().matches(NAME)) {
            f.put("name", "Write the session as 2026/2027.");
        } else {
            String[] y = name.trim().split("/");
            if (Integer.parseInt(y[1]) != Integer.parseInt(y[0]) + 1) f.put("name", "The second year must follow the first, as in 2026/2027.");
        }
        SemesterIn first = null, second = null;
        if (semesters != null) {
            for (int i = 0; i < semesters.size(); i++) {
                SemesterIn s = semesters.get(i);
                if (s == null || s.number() == null || (s.number() != 1 && s.number() != 2)) {
                    f.put("semesters[" + i + "].number", "A session has a first (1) and a second (2) semester.");
                    continue;
                }
                if (s.number() == 1) { if (first != null) f.put("semesters[" + i + "].number", "The first semester is listed twice."); first = s; }
                else { if (second != null) f.put("semesters[" + i + "].number", "The second semester is listed twice."); second = s; }
            }
        }
        checkSemester(first, indexOf(semesters, first), "first", complete, f);
        checkSemester(second, indexOf(semesters, second), "second", complete, f);
        if (first != null && second != null && first.endsOn() != null && second.startsOn() != null && !second.startsOn().isAfter(first.endsOn())) {
            f.put("semesters[" + indexOf(semesters, second) + "].startsOn", "The second semester must start after the first one ends (" + DAY.format(first.endsOn()) + ").");
        }
        if (complete) {
            if (first == null || second == null) f.putIfAbsent("semesters", "Add both semesters' dates before making the session active.");
            boolean anyWindow = (first != null && first.registration() != null && first.registration().opensOn() != null)
                    || (second != null && second.registration() != null && second.registration().opensOn() != null);
            if (!anyWindow) f.putIfAbsent("semesters", "Set the registration window before making the session active.");
        }
        return f;
    }

    private static int indexOf(List<SemesterIn> list, SemesterIn s) {
        return list == null || s == null ? 0 : list.indexOf(s);
    }

    private static void checkSemester(SemesterIn s, int i, String which, boolean complete, Map<String, String> f) {
        String k = "semesters[" + i + "].";
        if (s == null) return;
        if (complete && s.startsOn() == null) f.put(k + "startsOn", "Choose when the " + which + " semester starts.");
        if (complete && s.endsOn() == null) f.put(k + "endsOn", "Choose when the " + which + " semester ends.");
        if ((s.startsOn() == null) != (s.endsOn() == null) && !complete) {
            f.put(k + (s.startsOn() == null ? "startsOn" : "endsOn"), "Give both the start and the end of the " + which + " semester, or neither.");
        }
        if (s.startsOn() != null && s.endsOn() != null && !s.endsOn().isAfter(s.startsOn())) {
            f.put(k + "endsOn", "The " + which + " semester must end after it starts.");
        }
        Registration r = s.registration();
        if (r == null || (r.opensOn() == null && r.closesOn() == null && r.lateClosesOn() == null)) return;
        if (r.opensOn() == null) { f.put(k + "registration.opensOn", "Choose when registration opens."); return; }
        if (r.closesOn() == null) { f.put(k + "registration.closesOn", "Choose when registration closes."); return; }
        if (r.closesOn().isBefore(r.opensOn())) f.put(k + "registration.closesOn", "Registration must close on or after the day it opens.");
        else if (r.lateClosesOn() != null && r.lateClosesOn().isBefore(r.closesOn())) {
            f.put(k + "registration.lateClosesOn", "Late registration must end on or after normal registration closes.");
        } else if (s.endsOn() != null && (r.lateClosesOn() != null ? r.lateClosesOn() : r.closesOn()).isAfter(s.endsOn())) {
            f.put(k + "registration.closesOn", "Registration must close before the " + which + " semester ends (" + DAY.format(s.endsOn()) + ").");
        }
    }

    /** Two date ranges overlap when each starts on or before the other ends. */
    public static boolean overlap(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) return false;
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }

    /** "2–16 Feb", "28 Jan – 3 Feb", "20 Dec 2026 – 5 Jan 2027". */
    public static String range(LocalDate a, LocalDate b, boolean withYear) {
        String y = withYear ? " " + b.getYear() : "";
        if (a.getYear() != b.getYear()) return DAY.format(a) + " – " + DAY.format(b);
        if (a.getMonth() == b.getMonth()) return a.getDayOfMonth() + "–" + b.getDayOfMonth() + " " + month(b) + y;
        return a.getDayOfMonth() + " " + month(a) + " – " + b.getDayOfMonth() + " " + month(b) + y;
    }

    public static String month(LocalDate d) {
        return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US).replace(".", "");
    }
}
