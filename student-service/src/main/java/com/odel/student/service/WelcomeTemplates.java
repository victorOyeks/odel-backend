package com.odel.student.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The welcome email's wording (W-20d): fields written {first_name}, filled per student. Pure, so the checks and the
 * filling are unit-tested. A field the template uses but the email cannot fill is refused when the template is saved.
 */
public final class WelcomeTemplates {
    private static final Pattern FIELD = Pattern.compile("\\{\\s*([A-Za-z_]+)\\s*}");

    public record Field(String key, String label, String example) {}

    public static final List<Field> FIELDS = List.of(
            new Field("first_name", "First name", "Adeola"),
            new Field("surname", "Surname", "Okafor"),
            new Field("full_name", "Full name", "Adeola Okafor"),
            new Field("matric", "Matric number", "WU/ODL/24/0153"),
            new Field("programme", "Programme", "B.Sc. Computer Science"),
            new Field("level", "Level", "200L"),
            new Field("centre_name", "Study centre", "Akure Study Centre"),
            new Field("centre_phone", "Centre phone", "0803 555 0117"),
            new Field("session", "Session", "2026/2027"),
            new Field("activation_link", "Activation link", "https://wesley.odel.ng/activate?matric=WU%2FODL%2F24%2F0153"),
            new Field("university_name", "University", "Wesley University"));

    private static final Set<String> KEYS = new HashSet<>(FIELDS.stream().map(Field::key).toList());

    public static final String DEFAULT_SUBJECT = "Welcome to {university_name} ODeL — activate your account";
    public static final String DEFAULT_BODY = """
            Welcome, {first_name}

            You have been admitted to {programme}, {level}, at {centre_name}. Your matric number is {matric}.

            Activate your portal account to see your courses, join live classes and pay your platform fee. It takes about 3 minutes:
            {activation_link}

            Questions? Call your study centre on {centre_phone} or reply to this email.""";

    private WelcomeTemplates() {}

    /** Field errors for the editor: unknown fields, a missing activation link, empty or too long text. */
    public static Map<String, String> check(String subject, String body) {
        Map<String, String> f = new LinkedHashMap<>();
        if (subject == null || subject.isBlank()) f.put("subject", "Enter a subject.");
        else if (subject.length() > 200) f.put("subject", "Keep the subject under 200 characters.");
        else unknown(subject).ifPresent(u -> f.put("subject", "Unknown field {" + u + "}. Use one of the fields below."));
        if (body == null || body.isBlank()) f.put("body", "Write the email.");
        else if (body.length() > 10_000) f.put("body", "Keep the email under 10,000 characters.");
        else {
            unknown(body).ifPresent(u -> f.put("body", "Unknown field {" + u + "}. Use one of the fields below."));
            if (!f.containsKey("body") && !fields(body).contains("activation_link")) {
                f.put("body", "Add {activation_link}: it is how the student activates the account.");
            }
        }
        return f;
    }

    public static Set<String> fields(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
        return out;
    }

    private static Optional<String> unknown(String text) {
        return fields(text).stream().filter(k -> !KEYS.contains(k)).findFirst();
    }

    /** Fills {field} with the student's value; a field with no value becomes empty text. */
    public static String render(String text, Map<String, String> values) {
        Matcher m = FIELD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String v = KEYS.contains(key) ? values.getOrDefault(key, "") : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static Map<String, String> examples() {
        Map<String, String> m = new HashMap<>();
        FIELDS.forEach(f -> m.put(f.key(), f.example()));
        return m;
    }
}
