package com.odel.student.service;

import com.odel.student.dto.AcademicLookup;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks a portal CSV row by row (W-19) without saving anything: errors keep a row out, warnings let it in.
 * A matric number already on the platform updates that student. Pure: the caller supplies the academic lookup,
 * the students already on the platform and a way to ask which emails other accounts use.
 */
public final class ImportValidator {
    public static final int MAX_ROWS = 20_000;
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");
    private static final Pattern MATRIC = Pattern.compile("^[A-Z0-9/\\-.]{3,40}$");
    private static final Pattern SESSION = Pattern.compile("^(\\d{4})\\s*/\\s*(\\d{4})$");
    private static final Pattern LEVEL = Pattern.compile("^(?:level\\s*)?(\\d{3})\\s*l?$", Pattern.CASE_INSENSITIVE);
    private static final NumberFormat ROWS = NumberFormat.getIntegerInstance(Locale.UK);

    public static final List<String> COLUMNS = List.of("matric_number", "surname", "first_name", "other_names", "programme_code", "level",
            "study_centre", "email", "phone", "gender", "date_of_birth", "entry_session");
    public static final List<String> REQUIRED = List.of("matric_number", "surname", "first_name", "programme_code", "level", "study_centre");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("matric", "matric_number"), Map.entry("matric_no", "matric_number"), Map.entry("matricno", "matric_number"),
            Map.entry("matriculation_number", "matric_number"), Map.entry("matric_num", "matric_number"),
            Map.entry("last_name", "surname"), Map.entry("lastname", "surname"), Map.entry("family_name", "surname"),
            Map.entry("firstname", "first_name"), Map.entry("given_name", "first_name"),
            Map.entry("othernames", "other_names"), Map.entry("middle_name", "other_names"), Map.entry("middle_names", "other_names"),
            Map.entry("programme", "programme_code"), Map.entry("program", "programme_code"), Map.entry("program_code", "programme_code"),
            Map.entry("centre", "study_centre"), Map.entry("center", "study_centre"), Map.entry("study_center", "study_centre"),
            Map.entry("centre_code", "study_centre"), Map.entry("study_centre_code", "study_centre"),
            Map.entry("email_address", "email"), Map.entry("e_mail", "email"),
            Map.entry("phone_number", "phone"), Map.entry("mobile", "phone"), Map.entry("gsm", "phone"), Map.entry("telephone", "phone"),
            Map.entry("sex", "gender"), Map.entry("dob", "date_of_birth"), Map.entry("birth_date", "date_of_birth"),
            Map.entry("session", "entry_session"), Map.entry("admission_session", "entry_session"));

    private ImportValidator() {}

    // ---------------------------------------------------------------- types

    public enum Severity { ERROR, WARNING }

    public enum Outcome { NEW, UPDATED, UNCHANGED, ERROR }

    public record Problem(int row, String matricNumber, String name, Severity severity, String field, String message) {}

    /** A student as the file (or the platform) has them, normalised. */
    public record StudentData(String matricNumber, String surname, String firstName, String otherNames, String email, String phone,
                              String gender, LocalDate dateOfBirth, AcademicLookup.Programme programme, Integer level,
                              AcademicLookup.Centre centre, String entrySession) {
        public String fullName() {
            return (firstName == null ? "" : firstName) + (otherNames == null || otherNames.isBlank() ? "" : " " + otherNames)
                    + (surname == null ? "" : " " + surname);
        }
    }

    /** What is on the platform already for a matric number (for the update comparison). */
    public record Existing(UUID id, String surname, String firstName, String otherNames, String email, String phone, String gender,
                           LocalDate dateOfBirth, String programmeCode, int level, String centreCode, String entrySession, String status) {}

    public record Row(int line, List<String> raw, StudentData data, Outcome outcome, UUID existingId, List<Problem> problems) {}

    public record Result(List<String> header, List<Row> rows, List<Problem> problems, int total, int ready, int added, int updated,
                         int unchanged, int errors, int warnings) {}

    /** A problem with the whole file (no rows are checked). */
    public static class FileProblem extends RuntimeException {
        public final String code;
        public final Map<String, String> fields;

        public FileProblem(String code, String message, Map<String, String> fields) {
            super(message);
            this.code = code;
            this.fields = fields;
        }
    }

    public record EmailCheck(String matricNumber, String email) {}

    // ---------------------------------------------------------------- the check

    /**
     * @param emailsTakenElsewhere given the (matric, email) pairs of the file, returns the lower-case emails another account uses
     */
    public static Result validate(List<CsvParser.Record> records, AcademicLookup lookup, Map<String, Existing> existing,
                                  Function<List<EmailCheck>, Set<String>> emailsTakenElsewhere) {
        if (records.isEmpty()) throw new FileProblem("empty_file", "The file is empty. Export it again from the portal.", null);
        List<String> header = records.get(0).fields().stream().map(ImportValidator::column).toList();
        if (header.stream().noneMatch(REQUIRED::contains)) {
            throw new FileProblem("no_header", "The first row must be the column names. Download the template to see them.", null);
        }
        Map<String, String> missing = new LinkedHashMap<>();
        for (String r : REQUIRED) if (!header.contains(r)) missing.put(r, "Add the column " + r + ".");
        if (!missing.isEmpty()) {
            throw new FileProblem("missing_columns", "Some columns we need are missing: " + String.join(", ", missing.keySet())
                    + ". Download the template to see them.", missing);
        }
        List<CsvParser.Record> data = records.subList(1, records.size());
        if (data.isEmpty()) throw new FileProblem("empty_file", "The file has the column names but no students.", null);
        if (data.size() > MAX_ROWS) {
            throw new FileProblem("too_many_rows", "The file has " + ROWS.format(data.size()) + " rows; import at most "
                    + ROWS.format(MAX_ROWS) + " at a time. Split it into smaller files.", null);
        }
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) col.putIfAbsent(header.get(i), i);

        Index index = new Index(lookup);
        String session = lookup.currentSession() == null ? null : lookup.currentSession().name();

        // Pass 1: each row on its own.
        List<Draft> drafts = new ArrayList<>(data.size());
        for (CsvParser.Record rec : data) drafts.add(checkRow(rec, col, index, session));

        // Pass 2: across rows (duplicates in the file, unusual matric shapes, emails other accounts use).
        Map<String, List<Draft>> byMatric = new HashMap<>();
        Map<String, List<Draft>> byEmail = new HashMap<>();
        Map<String, Integer> shapes = new HashMap<>();
        for (Draft d : drafts) {
            if (d.matric != null) {
                byMatric.computeIfAbsent(d.matric, k -> new ArrayList<>()).add(d);
                shapes.merge(shape(d.matric), 1, Integer::sum);
            }
            if (d.email != null) byEmail.computeIfAbsent(d.email, k -> new ArrayList<>()).add(d);
        }
        byMatric.values().stream().filter(l -> l.size() > 1).forEach(l -> {
            String msg = "Matric number appears " + times(l.size()) + " in this file (rows " + lines(l) + ")";
            l.forEach(d -> d.error("matric_number", msg));
        });
        byEmail.values().stream().filter(l -> l.size() > 1).forEach(l -> {
            String msg = "Email " + l.get(0).email + " appears " + times(l.size()) + " in this file (rows " + lines(l)
                    + "). Each student needs their own email";
            l.forEach(d -> d.error("email", msg));
        });
        var common = shapes.entrySet().stream().max(Map.Entry.comparingByValue());
        if (common.isPresent() && drafts.size() >= 5 && common.get().getValue() * 2 >= drafts.size()) {
            String usual = common.get().getKey();
            String example = drafts.stream().filter(d -> d.matric != null && shape(d.matric).equals(usual)).findFirst().map(d -> d.matric).orElse(usual);
            for (Draft d : drafts) {
                if (d.matric != null && !shape(d.matric).equals(usual)) {
                    d.warn("matric_number", "Matric number \"" + d.matric + "\" looks different from most in this file (like \"" + example + "\"). Check it");
                }
            }
        }
        List<EmailCheck> checks = drafts.stream().filter(d -> d.email != null && d.matric != null)
                .map(d -> new EmailCheck(d.matric, d.email)).toList();
        Set<String> taken = checks.isEmpty() ? Set.of() : emailsTakenElsewhere.apply(checks);
        for (Draft d : drafts) {
            if (d.email != null && taken.contains(d.email.toLowerCase(Locale.ROOT))) {
                d.error("email", "Email " + d.email + " is already used by another account");
            }
        }

        // Pass 3: new, updated or unchanged.
        List<Row> rows = new ArrayList<>(drafts.size());
        List<Problem> problems = new ArrayList<>();
        int added = 0, updated = 0, unchanged = 0, errorRows = 0, warningRows = 0;
        for (Draft d : drafts) {
            Existing ex = d.matric == null ? null : existing.get(d.matric);
            StudentData sd = d.data();
            Outcome outcome;
            if (d.hasError()) {
                outcome = Outcome.ERROR;
            } else if (ex == null) {
                outcome = Outcome.NEW;
            } else {
                if ("ACTIVE".equals(ex.status()) && sd.email() != null && ex.email() != null && !sd.email().equalsIgnoreCase(ex.email())) {
                    d.warn("email", "Email not changed: the student has already activated. Change it on the student's record");
                }
                sd = merge(sd, ex);
                outcome = same(sd, ex) ? Outcome.UNCHANGED : Outcome.UPDATED;
            }
            switch (outcome) {
                case NEW -> added++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case ERROR -> errorRows++;
            }
            if (outcome != Outcome.ERROR && d.problems.stream().anyMatch(p -> p.severity() == Severity.WARNING)) warningRows++;
            rows.add(new Row(d.line, d.raw, sd, outcome, ex == null ? null : ex.id(), List.copyOf(d.problems)));
            problems.addAll(d.problems);
        }
        problems.sort(Comparator.comparing((Problem p) -> p.severity() == Severity.ERROR ? 0 : 1).thenComparingInt(Problem::row));
        return new Result(header, rows, problems, drafts.size(), added + updated + unchanged, added, updated, unchanged, errorRows, warningRows);
    }

    // ---------------------------------------------------------------- one row

    private static final class Draft {
        final int line;
        final List<String> raw;
        final List<Problem> problems = new ArrayList<>();
        String matric, surname, firstName, otherNames, email, phone, gender, entrySession;
        LocalDate dob;
        AcademicLookup.Programme programme;
        Integer level;
        AcademicLookup.Centre centre;

        Draft(int line, List<String> raw) {
            this.line = line;
            this.raw = raw;
        }

        String name() {
            String n = ((firstName == null ? "" : firstName) + " " + (surname == null ? "" : surname)).trim();
            return n.isEmpty() ? null : n;
        }

        void error(String field, String message) {
            problems.add(new Problem(line, matric, name(), Severity.ERROR, field, message));
        }

        void warn(String field, String message) {
            problems.add(new Problem(line, matric, name(), Severity.WARNING, field, message));
        }

        boolean hasError() {
            return problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
        }

        StudentData data() {
            return new StudentData(matric, surname, firstName, otherNames, email, phone, gender, dob, programme, level, centre, entrySession);
        }
    }

    private static Draft checkRow(CsvParser.Record rec, Map<String, Integer> col, Index index, String currentSession) {
        Draft d = new Draft(rec.line(), rec.fields());
        String matricRaw = value(rec, col, "matric_number");
        d.surname = name(value(rec, col, "surname"));
        d.firstName = name(value(rec, col, "first_name"));
        d.otherNames = name(value(rec, col, "other_names"));
        if (matricRaw != null) {
            String m = matricRaw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            d.matric = m;
            if (!MATRIC.matcher(m).matches()) {
                d.error("matric_number", "Matric number \"" + matricRaw + "\" can only use letters, numbers and / - . (3 to 40 characters)");
            }
        } else {
            d.error("matric_number", "Matric number is missing");
        }
        if (d.surname == null && d.firstName == null) d.error("surname", "Name is missing (surname and first name)");
        else if (d.surname == null) d.error("surname", "Surname is missing");
        else if (d.firstName == null) d.error("first_name", "First name is missing");
        else if (d.surname.length() > 100 || d.firstName.length() > 100 || (d.otherNames != null && d.otherNames.length() > 150)) {
            d.error("surname", "A name is too long (at most 100 characters)");
        }

        String prog = value(rec, col, "programme_code");
        if (prog == null) d.error("programme_code", "Programme code is missing");
        else {
            d.programme = index.programme(prog);
            if (d.programme == null) {
                String s = index.suggestProgramme(prog);
                d.error("programme_code", "Programme code \"" + prog + "\" unknown. "
                        + (s == null ? "Check the codes in Academic structure" : "Expected \"" + s + "\""));
            }
        }

        String lvl = value(rec, col, "level");
        if (lvl == null) d.error("level", "Level is missing");
        else {
            Matcher m = LEVEL.matcher(lvl.trim());
            Integer level = m.matches() ? Integer.valueOf(m.group(1)) : null;
            if (level == null || level % 100 != 0 || level < 100 || level > 900) {
                d.error("level", "Level \"" + lvl + "\" is not a level. Use 100, 200, 300 and so on");
            } else if (d.programme != null && d.programme.levels() != null && !d.programme.levels().isEmpty() && !d.programme.levels().contains(level)) {
                List<Integer> ls = d.programme.levels().stream().sorted().toList();
                d.error("level", "Level " + level + " is not offered for " + d.programme.name() + " (" + ls.get(0) + "L–" + ls.get(ls.size() - 1) + "L)");
            } else {
                d.level = level;
            }
        }

        String centre = value(rec, col, "study_centre");
        if (centre == null) d.error("study_centre", "Study centre is missing");
        else {
            d.centre = index.centre(centre);
            if (d.centre == null) {
                String s = index.suggestCentre(centre);
                d.error("study_centre", "Study centre \"" + centre + "\" not found." + (s == null ? " Check the centres list" : " Did you mean \"" + s + "\"?"));
            }
        }

        String email = value(rec, col, "email");
        if (email == null) {
            d.warn("email", "No email: this student cannot get the welcome email or a code until one is added");
        } else if (!EMAIL.matcher(email.trim()).matches() || email.trim().length() > 320) {
            d.error("email", "Email \"" + email + "\" is not a valid address");
        } else {
            d.email = email.trim().toLowerCase(Locale.ROOT);
        }

        String phone = value(rec, col, "phone");
        if (phone != null) {
            d.phone = phone(phone);
            if (d.phone == null) {
                String digits = phone.replaceAll("\\D", "");
                d.warn("phone", "Phone \"" + phone + "\" is " + (digits.length() < 11 ? "too short" : "not a Nigerian number") + "; it is left blank");
            }
        }

        String gender = value(rec, col, "gender");
        if (gender != null) {
            String g = gender.trim().toUpperCase(Locale.ROOT);
            if (g.equals("M") || g.equals("MALE")) d.gender = "M";
            else if (g.equals("F") || g.equals("FEMALE")) d.gender = "F";
            else d.warn("gender", "Gender \"" + gender + "\" is not M or F; it is left blank");
        }

        String dob = value(rec, col, "date_of_birth");
        if (dob != null) {
            d.dob = date(dob);
            if (d.dob == null) d.warn("date_of_birth", "Date of birth \"" + dob + "\" is not a date (use YYYY-MM-DD); it is left blank");
        }

        String session = value(rec, col, "entry_session");
        if (session != null) {
            Matcher m = SESSION.matcher(session.trim());
            if (m.matches() && Integer.parseInt(m.group(2)) == Integer.parseInt(m.group(1)) + 1) d.entrySession = m.group(1) + "/" + m.group(2);
            else {
                d.entrySession = currentSession;
                d.warn("entry_session", "Entry session \"" + session + "\" is not like 2024/2025; the current session is used");
            }
        } else {
            d.entrySession = currentSession;
        }
        return d;
    }

    // ---------------------------------------------------------------- updates

    /** Blank values in the file never wipe what the platform has; an active student's email stays. */
    private static StudentData merge(StudentData f, Existing ex) {
        String email = "ACTIVE".equals(ex.status()) && ex.email() != null ? ex.email() : (f.email() != null ? f.email() : ex.email());
        return new StudentData(f.matricNumber(), f.surname(), f.firstName(), f.otherNames() != null ? f.otherNames() : ex.otherNames(),
                email, f.phone() != null ? f.phone() : ex.phone(), f.gender() != null ? f.gender() : ex.gender(),
                f.dateOfBirth() != null ? f.dateOfBirth() : ex.dateOfBirth(), f.programme(), f.level(), f.centre(),
                ex.entrySession() != null ? ex.entrySession() : f.entrySession()); // the entry session never changes on a re-import
    }

    private static boolean same(StudentData d, Existing ex) {
        return Objects.equals(d.surname(), ex.surname()) && Objects.equals(d.firstName(), ex.firstName())
                && Objects.equals(d.otherNames(), ex.otherNames()) && Objects.equals(lower(d.email()), lower(ex.email()))
                && Objects.equals(d.phone(), ex.phone()) && Objects.equals(d.gender(), ex.gender())
                && Objects.equals(d.dateOfBirth(), ex.dateOfBirth())
                && d.programme().code().equalsIgnoreCase(ex.programmeCode()) && d.level() == ex.level()
                && d.centre().code().equalsIgnoreCase(ex.centreCode());
    }

    // ---------------------------------------------------------------- helpers

    /** Programme and centre codes, names and short names, matched without regard to case or spacing. */
    static final class Index {
        final Map<String, AcademicLookup.Programme> programmes = new HashMap<>();
        final Map<String, AcademicLookup.Centre> centres = new HashMap<>();
        final List<AcademicLookup.Programme> programmeList;
        final List<AcademicLookup.Centre> centreList;

        Index(AcademicLookup lookup) {
            programmeList = lookup.programmes() == null ? List.of() : lookup.programmes();
            centreList = lookup.centres() == null ? List.of() : lookup.centres();
            for (var p : programmeList) {
                programmes.putIfAbsent(key(p.code()), p);
                if (p.name() != null) programmes.putIfAbsent(key(p.name()), p);
            }
            for (var c : centreList) {
                centres.putIfAbsent(key(c.code()), c);
                if (c.name() != null) centres.putIfAbsent(key(c.name()), c);
                if (c.shortName() != null) centres.putIfAbsent(key(c.shortName()), c);
            }
        }

        AcademicLookup.Programme programme(String v) { return programmes.get(key(v)); }

        AcademicLookup.Centre centre(String v) { return centres.get(key(v)); }

        String suggestProgramme(String v) {
            return best(v, programmeList.stream().map(p -> Map.entry(p.code(), p.code())).toList());
        }

        String suggestCentre(String v) {
            List<Map.Entry<String, String>> c = new ArrayList<>();
            for (var x : centreList) {
                String label = x.shortName() != null ? x.shortName() : x.name();
                c.add(Map.entry(x.code(), label));
                if (x.name() != null) c.add(Map.entry(x.name(), label));
                if (x.shortName() != null) c.add(Map.entry(x.shortName(), label));
            }
            return best(v, c);
        }

        /** The closest candidate: one name inside the other, or at most two letters apart. */
        private static String best(String value, List<Map.Entry<String, String>> candidates) {
            String v = loose(value);
            if (v.length() < 2) return null;
            String found = null;
            int bestScore = Integer.MAX_VALUE;
            for (var c : candidates) {
                String k = loose(c.getKey());
                if (k.isEmpty()) continue;
                int score;
                if (k.contains(v) || v.contains(k)) score = Math.abs(k.length() - v.length());
                else {
                    int dist = levenshtein(k, v);
                    if (dist > 2 || v.length() < 4) continue;
                    score = 100 + dist;
                }
                if (score < bestScore) {
                    bestScore = score;
                    found = c.getValue();
                }
            }
            return found;
        }
    }

    static String key(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String loose(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** A header cell as a canonical column name. */
    public static String column(String header) {
        String h = header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-.]+", "_").replaceAll("[^a-z0-9_]", "");
        return ALIASES.getOrDefault(h, h);
    }

    private static String value(CsvParser.Record rec, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= rec.fields().size()) return null;
        String v = rec.fields().get(i);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String name(String s) {
        if (s == null) return null;
        String n = s.trim().replaceAll("\\s+", " ");
        return n.isEmpty() ? null : n;
    }

    /** Nigerian numbers as 0803 412 4417; null when it is not one. */
    public static String phone(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("234") && digits.length() == 13) digits = "0" + digits.substring(3);
        if (digits.length() != 11 || !digits.startsWith("0")) return null;
        return digits.substring(0, 4) + " " + digits.substring(4, 7) + " " + digits.substring(7);
    }

    private static LocalDate date(String raw) {
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"))) {
            try {
                LocalDate d = LocalDate.parse(raw.trim(), f);
                if (d.getYear() > 1900 && d.isBefore(LocalDate.now())) return d;
            } catch (DateTimeParseException ignored) { }
        }
        return null;
    }

    static String shape(String matric) {
        return matric.replaceAll("[A-Z]", "A").replaceAll("[0-9]", "9");
    }

    private static String times(int n) {
        return n == 2 ? "twice" : n + " times";
    }

    private static String lines(List<Draft> l) {
        List<String> ns = l.stream().map(d -> ROWS.format(d.line)).toList();
        if (ns.size() == 1) return ns.get(0);
        return String.join(", ", ns.subList(0, ns.size() - 1)) + " and " + ns.get(ns.size() - 1);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /** The template's header row and one example row. */
    public static String template() {
        return String.join(",", COLUMNS) + "\r\n"
                + "WU/ODL/24/0153,Okafor,Adeola,Chiamaka,BSC-CSC-ODL,200,Akure,adeola.okafor@example.com,0803 412 4417,F,2003-05-14,2024/2025\r\n";
    }

    static Set<String> lowerSet(Collection<String> s) {
        return s.stream().map(x -> x.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
