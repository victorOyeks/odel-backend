package com.odel.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 students end-to-end (Build plan B1, steps 2 to 6), through the api-gateway of a running stack with the dev profile:
 * the ODeL admin imports the portal file (row-by-row check that saves nothing, then confirm), sends a test welcome email and
 * then the welcome to every student not yet active; Adeola activates with her matric number and an emailed code, sets a
 * password, accepts the privacy notice and reaches Home; she signs out and back in, gets locked after five wrong passwords,
 * resets her password by emailed code; the office suspends and reinstates her. Then the edge cases, tenant isolation for
 * every new endpoint, and the 3,504-row import timing.
 *
 * Run: ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test -Dtest=StudentsInJourneyTest
 * If the provider account already has two-step set up, ODEL_E2E_PROVIDER_TOTP_SECRET must hold its secret (as for B0).
 * Students' emails are on the reserved .test domain, so nothing is ever handed to the mail provider.
 */
@EnabledIfEnvironmentVariable(named = "ODEL_E2E_GATEWAY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudentsInJourneyTest {
    final String gw = System.getenv("ODEL_E2E_GATEWAY");
    final String base = Optional.ofNullable(System.getenv("ODEL_E2E_BASE_DOMAIN")).orElse("localhost:3000");
    final String console = Optional.ofNullable(System.getenv("ODEL_E2E_CONSOLE_HOST")).orElse("console." + base);
    final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36) + Integer.toString(new Random().nextInt(1296), 36);
    final String wesleySlug = "wes-stu-" + run, crestSlug = "crest-stu-" + run;
    final String wesleyHost = wesleySlug + "." + base, crestHost = crestSlug + "." + base;
    final String wesleyDomain = "wes-stu-" + run + ".edu.ng", crestDomain = "crest-stu-" + run + ".edu.ng";
    final String ngozi = "ngozi.adebayo@" + wesleyDomain, yemi = "yemi.adeoye@" + crestDomain;
    final String studentMail = "students-" + run + ".test";
    final String adeolaMatric = "WU/ODL/24/0153", adeolaEmail = "adeola.okafor@" + studentMail;
    final Set<String> usedCodes = new HashSet<>();

    Browser chuka, ngoziB, yemiB, adeola;
    String wesleyId, crestId, importId, sendId, adeolaId, musaId, password = "Adeola2026", fileKey;

    // ------------------------------------------------------------------ helpers

    String code(String secret) {
        long now = Instant.now().getEpochSecond() / 30;
        for (long s = now - 1; s <= now + 1; s++) {
            String c = PlatformBaseJourneyTest.totp(secret, s);
            if (usedCodes.add(secret + c)) return c;
        }
        throw new IllegalStateException("No unused code in the window");
    }

    void firstSignIn(Browser b, String email) {
        assertThat(b.googleSignIn(email)).startsWith("/auth/continue?next=");
        String secret = b.post("/api/v1/auth/mfa/setup", Map.of()).json().get("secret").asText();
        Browser.Res ok = b.post("/api/v1/auth/mfa/setup/confirm", Map.of("code", code(secret)));
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
    }

    String createUniversity(String name, String shortName, String slug, String domain, String admin, String adminEmail, String primary) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", name);
        u.put("shortName", shortName);
        u.put("slug", slug);
        u.put("googleDomain", domain);
        u.put("firstAdmin", Map.of("fullName", admin, "email", adminEmail));
        u.put("branding", Map.of("primaryColor", primary, "accentColor", "#C27808"));
        Browser.Res created = chuka.post("/api/v1/platform/tenants", u);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.json().get("id").asText();
    }

    /** The newest email to an address whose subject contains the text (dev list), waiting up to the given seconds. */
    JsonNode email(String to, String subjectPart, Set<String> skipBodies, int seconds) throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            JsonNode list = chuka.get("/api/v1/dev/emails?to=" + URLEncoder.encode(to, StandardCharsets.UTF_8)).json();
            for (JsonNode e : list) {
                if (e.get("subject").asText().contains(subjectPart) && !skipBodies.contains(e.get("body").asText())
                        && !e.get("status").asText().equals("QUEUED")) return e;
            }
            Thread.sleep(300);
        }
        return null;
    }

    final Set<String> seenCodeEmails = new HashSet<>();

    String codeFrom(String to, String subjectPart) throws InterruptedException {
        JsonNode e = email(to, subjectPart, seenCodeEmails, 60);
        assertThat(e).as("a code email to " + to + " within 60 seconds").isNotNull();
        seenCodeEmails.add(e.get("body").asText());
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(e.get("body").asText());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    List<String> actions(String tenantId) {
        List<String> out = new ArrayList<>();
        chuka.get("/api/v1/platform/audit?size=500&tenantId=" + tenantId).json().get("items").forEach(i -> out.add(0, i.get("action").asText()));
        return out;
    }

    static String row(String matric, String surname, String first, String programme, String level, String centre, String email, String phone) {
        return String.join(",", matric, surname, first, "", programme, level, centre, email, phone) + "\n";
    }

    static final String HEADER = "matric_number,surname,first_name,other_names,programme_code,level,study_centre,email,phone\n";

    /** The story's file in small: 30 good rows (one with no email, one with a bad phone) and 6 errors. */
    String smallFile() {
        StringBuilder csv = new StringBuilder(HEADER);
        csv.append(row(adeolaMatric, "Okafor", "Adeola", "BSC-CSC-ODL", "200L", "Akure", adeolaEmail, "0803 412 4417"));
        csv.append(row("WU/ODL/25/0042", "Ibrahim", "Musa", "BSC-IFT-ODL", "100", "Abuja", "", ""));            // warning: no email
        csv.append(row("WU/ODL/24/0688", "Adewale", "Bayo", "BSC-CSC-ODL", "200", "Akure", "bayo.adewale@" + studentMail, "0803-12")); // warning: phone
        String[] first = {"Tunde", "Chidinma", "Halima", "Grace", "Sade", "Emeka", "Ngozi", "Yusuf", "Bisi", "Kemi", "Femi", "Amaka", "Ifeanyi",
                "Zainab", "Tobi", "Uche", "Funke", "Dayo", "Nkechi", "Segun", "Aisha", "Chuka", "Bola", "Ruth", "Kunle", "Ada", "Wale"};
        String[] centres = {"Akure", "Lagos (Ikeja)", "Abuja", "OND", "Ibadan Study Centre"};
        for (int i = 0; i < first.length; i++) {
            csv.append(row(String.format("WU/ODL/24/%04d", 2000 + i), "Student" + i, first[i], i % 2 == 0 ? "BSC-CSC-ODL" : "BSC-IFT-ODL",
                    String.valueOf(100 * (1 + i % 4)), centres[i % centres.length], first[i].toLowerCase() + i + "@" + studentMail, "0806 555 01" + String.format("%02d", i)));
        }
        csv.append(row("WU/ODL/24/0201", "Musa", "Ibrahim", "BSC-CSC-ODL", "200", "Ikeja", "ibrahim.musa@" + studentMail, ""));   // bad centre
        csv.append(row("WU/ODL/24/1011", "Sani", "Fatima", "CSC-ODL", "200", "Akure", "fatima.sani@" + studentMail, ""));         // unknown programme
        csv.append(row("WU/ODL/24/0900", "Eze", "Chioma", "BSC-CSC-ODL", "200", "Akure", "chioma.eze@" + studentMail, ""));       // duplicate pair
        csv.append(row("WU/ODL/24/0900", "Eze", "Chika", "BSC-CSC-ODL", "200", "Akure", "chika.eze@" + studentMail, ""));
        csv.append(row("WU/ODL/24/0901", "", "", "BSC-CSC-ODL", "200", "Akure", "noname@" + studentMail, ""));                     // missing name
        csv.append(row("WU/ODL/24/0902", "Obi", "Ken", "BSC-CSC-ODL", "200", "Akure", "ken.obi.at.mail", ""));                    // bad email
        return csv.toString();
    }

    /** The story's full file: 3,478 good rows and 26 errors (3,504 rows). */
    static String bigFile(String domain) {
        StringBuilder csv = new StringBuilder(HEADER);
        String[] centres = {"Akure", "Lagos (Ikeja)", "Abuja", "Ondo (main campus)", "Ibadan"};
        for (int i = 0; i < 3478; i++) {
            csv.append(row(String.format("WU/ODL/24/%05d", i), "Surname" + i, "First" + i, i % 3 == 0 ? "BSC-IFT-ODL" : "BSC-CSC-ODL",
                    String.valueOf(100 * (1 + i % 4)), centres[i % 5], i % 50 == 0 ? "" : "s" + i + "@" + domain, "0803 555 " + String.format("%04d", i % 10000)));
        }
        for (int i = 0; i < 3; i++) for (int k = 0; k < 2; k++) csv.append(row("WU/ODL/24/D" + i, "Dup" + i, "Twin" + k, "BSC-CSC-ODL", "200", "Akure", "", ""));
        for (int i = 0; i < 10; i++) csv.append(row("WU/ODL/24/C" + i, "Centre" + i, "Bad", "BSC-CSC-ODL", "200", "Ikeja", "", ""));
        for (int i = 0; i < 5; i++) csv.append(row("WU/ODL/24/P" + i, "Prog" + i, "Bad", "CSC-ODL", "200", "Akure", "", ""));
        for (int i = 0; i < 3; i++) csv.append(row("WU/ODL/24/N" + i, "", "", "BSC-CSC-ODL", "200", "Akure", "", ""));
        for (int i = 0; i < 2; i++) csv.append(row("WU/ODL/24/E" + i, "Mail" + i, "Bad", "BSC-CSC-ODL", "200", "Akure", "bad" + i + "-at-mail", ""));
        return csv.toString();
    }

    String upload(Browser b, String name, String csv) {
        JsonNode link = b.post("/api/v1/imports/students/uploads", Map.of("fileName", name, "contentType", "text/csv", "size", csv.length())).json();
        Browser.Res put = b.send("PUT", link.get("uploadUrl").asText(), csv, "text/csv");
        assertThat(put.status()).as(put.body()).isEqualTo(200);
        return link.get("key").asText();
    }

    Browser student(String host) {
        Browser b = new Browser(gw, host);
        b.get("/api/v1/public/context");
        return b;
    }

    // ------------------------------------------------------------------ setup

    @BeforeAll
    void universities() {
        chuka = new Browser(gw, console);
        String chukaEmail = Optional.ofNullable(System.getenv("ODEL_E2E_PROVIDER_EMAIL")).orElse("chuka.obi@odel.dev");
        chuka.googleSignIn(chukaEmail);
        String stage = chuka.get("/api/v1/session").json().get("stage").asText();
        if (stage.equals("MFA_SETUP")) {
            chuka = new Browser(gw, console);
            firstSignIn(chuka, chukaEmail);
        } else {
            String secret = System.getenv("ODEL_E2E_PROVIDER_TOTP_SECRET");
            Assumptions.assumeTrue(secret != null, "provider already has two-step; set ODEL_E2E_PROVIDER_TOTP_SECRET");
            assertThat(chuka.post("/api/v1/auth/mfa/verify", Map.of("code", code(secret))).status()).isEqualTo(200);
        }
        wesleyId = createUniversity("Wesley University", "Wesley ODeL", wesleySlug, wesleyDomain, "Ngozi Adebayo", ngozi, "#1A2368");
        crestId = createUniversity("Crestfield University", "Crestfield ODL", crestSlug, crestDomain, "Yemi Adeoye", yemi, "#0F6E6E");
        ngoziB = new Browser(gw, wesleyHost);
        firstSignIn(ngoziB, ngozi);
        yemiB = new Browser(gw, crestHost);
        firstSignIn(yemiB, yemi);
        academicSetUp(ngoziB);
        academicSetUp(yemiB);
    }

    /**
     * The programmes and centres the files use, created through academic-service's API (W-12, W-14); student-service reads
     * them back from GET /internal/academic/reference (centre phones included, for {centre_phone} and E-03).
     */
    void academicSetUp(Browser admin) {
        assertThat(admin.get("/api/v1/academic/structure").status()).as("academic-service must be running").isEqualTo(200);
        String faculty = created(admin.post("/api/v1/academic/faculties", Map.of("name", "Faculty of Science", "code", "SCI")));
        String dept = created(admin.post("/api/v1/academic/departments", Map.of("facultyId", faculty, "name", "Computer Science", "code", "CSC")));
        for (String[] p : new String[][]{{"B.Sc. Computer Science", "BSC-CSC-ODL"}, {"B.Sc. Info. Technology", "BSC-IFT-ODL"}}) {
            created(admin.post("/api/v1/academic/programmes", Map.of("departmentId", dept, "name", p[0], "code", p[1], "degree", "B.Sc.",
                    "durationYears", 4, "entryLevels", List.of(100, 200, 300, 400))));
        }
        for (String[] c : new String[][]{{"Akure Study Centre", "AKR", "Ondo", AKURE_PHONE}, {"Lagos (Ikeja)", "LAG", "Lagos", "0803 555 0118"},
                {"Abuja Study Centre", "ABJ", "FCT", ABUJA_PHONE}, {"Ondo (main campus)", "OND", "Ondo", "0803 555 0120"}, {"Ibadan Study Centre", "IBD", "Oyo", "0803 555 0121"}}) {
            created(admin.post("/api/v1/academic/centres", Map.of("name", c[0], "code", c[1], "address", "1 Campus Road", "state", c[2], "phone", c[3])));
        }
    }

    static final String AKURE_PHONE = "0803 555 0117", ABUJA_PHONE = "0803 555 0119";

    static String created(Browser.Res r) {
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().get("id").asText();
    }

    // ------------------------------------------------------------------ 2. import

    @Test
    @Order(1)
    void adminImportsThePortalFile() {
        Browser.Res template = ngoziB.get("/api/v1/imports/students/template.csv");
        assertThat(template.status()).isEqualTo(200);
        assertThat(template.body()).contains("matric_number,surname,first_name,other_names,programme_code,level,study_centre,email,phone");

        assertThat(ngoziB.post("/api/v1/imports/students/uploads", Map.of("fileName", "portal.xlsx", "size", 100)).json().at("/fields/fileName").asText())
                .contains("CSV");
        fileKey = upload(ngoziB, "portal_export_" + run + ".csv", smallFile());

        // W-19: the report, nothing saved.
        Browser.Res checked = ngoziB.post("/api/v1/imports/students/validate", Map.of("key", fileKey, "fileName", "portal_export.csv"));
        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        JsonNode report = checked.json();
        importId = report.get("importId").asText();
        assertThat(report.get("totalRows").asInt()).isEqualTo(36);
        assertThat(report.at("/ready/count").asInt()).isEqualTo(30);
        assertThat(report.at("/ready/new").asInt()).isEqualTo(30);
        assertThat(report.at("/errors/count").asInt()).isEqualTo(6);
        assertThat(report.at("/warnings/count").asInt()).isEqualTo(2);
        List<String> problems = new ArrayList<>();
        report.get("problems").forEach(p -> problems.add(p.get("severity").asText() + " " + p.get("row").asInt() + " " + p.get("message").asText()));
        assertThat(problems).contains(
                "ERROR 32 Study centre \"Ikeja\" not found. Did you mean \"Lagos (Ikeja)\"?",
                "ERROR 33 Programme code \"CSC-ODL\" unknown. Expected \"BSC-CSC-ODL\"",
                "ERROR 34 Matric number appears twice in this file (rows 34 and 35)",
                "ERROR 36 Name is missing (surname and first name)",
                "ERROR 37 Email \"ken.obi.at.mail\" is not a valid address",
                "WARNING 4 Phone \"0803-12\" is too short; it is left blank");
        assertThat(problems).anyMatch(p -> p.startsWith("WARNING 3 No email"));
        assertThat(ngoziB.get("/api/v1/students").json().get("total").asInt()).as("nothing saved by the check").isZero();
        assertThat(ngoziB.get("/api/v1/imports/students/" + importId + "/problems?severity=ERROR").json().get("total").asInt()).isEqualTo(6);
        assertThat(ngoziB.get("/api/v1/imports/students/" + importId + "/problems.csv").body()).contains("Ikeja").contains("row,type,problem");
        assertThat(ngoziB.get("/api/v1/imports/students").json().get("total").asInt()).as("a check is not in the history").isZero();

        // Confirm: only the good rows.
        Browser.Res confirmed = ngoziB.post("/api/v1/imports/students/" + importId + "/confirm", Map.of());
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.json().get("added").asInt()).isEqualTo(30);
        assertThat(confirmed.json().get("skipped").asInt()).isEqualTo(6);
        assertThat(confirmed.json().at("/importedBy/fullName").asText()).isEqualTo("Ngozi Adebayo");
        assertThat(ngoziB.post("/api/v1/imports/students/" + importId + "/confirm", Map.of()).json().get("error").asText()).isEqualTo("already_imported");
        JsonNode list = ngoziB.get("/api/v1/students?size=100").json();
        assertThat(list.get("total").asInt()).isEqualTo(30);
        JsonNode history = ngoziB.get("/api/v1/imports/students").json();
        assertThat(history.at("/items/0/added").asInt()).isEqualTo(30);
        assertThat(history.at("/items/0/errors").asInt()).isEqualTo(6);
        assertThat(history.at("/items/0/importedBy/fullName").asText()).isEqualTo("Ngozi Adebayo");

        // The same file again: every row is known, nobody is duplicated.
        JsonNode again = ngoziB.post("/api/v1/imports/students/validate", Map.of("key", upload(ngoziB, "again.csv", smallFile()), "fileName", "again.csv")).json();
        assertThat(again.at("/ready/new").asInt()).isZero();
        assertThat(again.at("/ready/unchanged").asInt()).isEqualTo(30);

        // W-22: search and filters.
        JsonNode found = ngoziB.get("/api/v1/students?q=okafor").json();
        assertThat(found.get("total").asInt()).isEqualTo(1);
        adeolaId = found.at("/items/0/id").asText();
        assertThat(found.at("/items/0/matricNumber").asText()).isEqualTo(adeolaMatric);
        assertThat(found.at("/items/0/programme/name").asText()).isEqualTo("B.Sc. Computer Science");
        assertThat(found.at("/items/0/centre/shortName").asText()).isEqualTo("Akure");
        assertThat(found.at("/items/0/statusLabel").asText()).isEqualTo("Not activated");
        assertThat(ngoziB.get("/api/v1/students?q=" + URLEncoder.encode("wu/odl/24/0153", StandardCharsets.UTF_8)).json().get("total").asInt()).isEqualTo(1);
        assertThat(ngoziB.get("/api/v1/students?q=" + URLEncoder.encode(adeolaEmail, StandardCharsets.UTF_8)).json().get("total").asInt()).isEqualTo(1);
        assertThat(ngoziB.get("/api/v1/students?centre=AKR&programme=BSC-CSC-ODL&level=200").json().get("items").findValuesAsText("matricNumber")).contains(adeolaMatric);
        assertThat(ngoziB.get("/api/v1/students?status=ACTIVE").json().get("total").asInt()).isZero();
        assertThat(ngoziB.get("/api/v1/students/filters").json().get("centres").size()).isGreaterThan(0);
        musaId = ngoziB.get("/api/v1/students?q=WU/ODL/25/0042").json().at("/items/0/id").asText();
    }

    // ------------------------------------------------------------------ 3. welcome emails

    @Test
    @Order(2)
    void adminSendsTheWelcomeEmails() throws Exception {
        JsonNode w = ngoziB.get("/api/v1/students/welcome").json();
        assertThat(w.at("/template/custom").asBoolean()).isFalse();
        assertThat(w.at("/audience/notActivated").asInt()).isEqualTo(30);
        assertThat(w.at("/audience/noEmail").asInt()).isEqualTo(1);
        assertThat(w.at("/preview/body").asText()).doesNotContain("{");
        assertThat(w.get("fields").findValuesAsText("key")).contains("first_name", "matric", "activation_link", "university_name");

        // W-20d: a wrong field is refused; the edited wording is saved.
        Browser.Res bad = ngoziB.send("PUT", "/api/v1/students/welcome/template", Map.of("subject", "Welcome", "body", "Hello {firstname} {activation_link}"), "application/json");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().at("/fields/body").asText()).contains("{firstname}");
        String body = "Hello {first_name},\nYou have been admitted to {programme} at {centre_name}. Your matric number is {matric}.\nActivate your portal account: {activation_link}\nQuestions? Call your study centre on {centre_phone}.";
        Browser.Res saved = ngoziB.send("PUT", "/api/v1/students/welcome/template",
                Map.of("subject", "Welcome to {university_name} ODeL — activate your account", "body", body), "application/json");
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(saved.json().at("/template/custom").asBoolean()).isTrue();

        // Test send to herself.
        Browser.Res test = ngoziB.post("/api/v1/students/welcome/test", Map.of());
        assertThat(test.json().get("sentTo").asText()).isEqualTo(ngozi);
        JsonNode testMail = email(ngozi, "[Test] Welcome to Wesley University ODeL", Set.of(), 30);
        assertThat(testMail).isNotNull();
        assertThat(testMail.get("status").asText()).isEqualTo("SENT");
        assertThat(testMail.get("body").asText()).contains("/activate?matric=").doesNotContain("{");

        // Send to all, in the background.
        Browser.Res started = ngoziB.post("/api/v1/students/welcome/sends", Map.of("remindAfterDays", 3));
        assertThat(started.status()).as(started.body()).isEqualTo(202);
        sendId = started.json().get("id").asText();
        assertThat(started.json().at("/totals/students").asInt()).isEqualTo(30);
        assertThat(started.json().get("reminderDueAt").asText()).isNotBlank();
        Browser.Res twice = ngoziB.post("/api/v1/students/welcome/sends", Map.of());
        assertThat(twice.status()).isIn(409);
        JsonNode progress = null;
        for (int i = 0; i < 120; i++) {
            progress = ngoziB.get("/api/v1/students/welcome/sends/" + sendId).json();
            if (progress.get("status").asText().equals("DONE")) break;
            Thread.sleep(500);
        }
        assertThat(progress.get("status").asText()).as(progress.toString()).isEqualTo("DONE");
        assertThat(progress.get("progressPercent").asInt()).isEqualTo(100);
        assertThat(progress.at("/totals/skippedNoEmail").asInt()).isEqualTo(1);
        assertThat(progress.at("/totals/skippedTestAddress").asInt()).as("students' .test addresses are recorded, never sent").isEqualTo(29);
        assertThat(progress.at("/totals/failed").asInt()).isZero();
        assertThat(progress.at("/totals/sending").asInt()).isZero();
        JsonNode noEmail = ngoziB.get("/api/v1/students/welcome/sends/" + sendId + "/deliveries?status=SKIPPED_NO_EMAIL").json();
        assertThat(noEmail.at("/items/0/matricNumber").asText()).isEqualTo("WU/ODL/25/0042");
        assertThat(noEmail.at("/items/0/reason").asText()).isEqualTo("No email on record");
        assertThat(ngoziB.get("/api/v1/students/welcome/sends/" + sendId + "/failed.csv").body()).contains("WU/ODL/25/0042").contains("Not sent: no email");

        JsonNode welcome = email(adeolaEmail, "Welcome to Wesley University ODeL", Set.of(), 30);
        assertThat(welcome.get("body").asText()).startsWith("Hello Adeola,")
                .contains("B.Sc. Computer Science at Akure Study Centre").contains("Call your study centre on " + AKURE_PHONE).contains(wesleyHost + "/activate?matric=WU%2FODL%2F24%2F0153");
        assertThat(ngoziB.get("/api/v1/students/" + adeolaId).json().get("status").asText()).isEqualTo("INVITED");
        assertThat(ngoziB.get("/api/v1/students/" + adeolaId).json().at("/account/welcomeSentAt").asText()).isNotBlank();
        assertThat(ngoziB.get("/api/v1/students/welcome").json().at("/lastSend/status").asText()).isEqualTo("DONE");
    }

    // ------------------------------------------------------------------ 4. activation

    @Test
    @Order(3)
    void adeolaActivatesAndReachesHome() throws Exception {
        adeola = student(wesleyHost);
        assertThat(adeola.get("/api/v1/auth/student/password-rules").json().get("minLength").asInt()).isEqualTo(8);
        assertThat(adeola.get("/api/v1/auth/student/privacy-notice").json().get("intro").asText()).startsWith("Wesley University protects your data");

        long asked = System.currentTimeMillis();
        Browser.Res start = adeola.post("/api/v1/auth/student/activation/start", Map.of("login", " wu/odl/24/0153 "));
        assertThat(start.status()).as(start.body()).isEqualTo(200);
        String activationId = start.json().get("activationId").asText();
        assertThat(start.json().get("maskedEmail").asText()).isEqualTo("a•••••@" + studentMail);
        assertThat(start.json().get("codeSent").asBoolean()).isTrue();
        assertThat(start.json().get("expiresInSeconds").asInt()).isBetween(890, 900);
        assertThat(start.json().at("/centre/name").asText()).isEqualTo("Akure Study Centre");
        assertThat(start.json().at("/student/firstName").asText()).isEqualTo("Adeola");
        JsonNode again = adeola.post("/api/v1/auth/student/activation/start", Map.of("login", adeolaMatric)).json();
        assertThat(again.get("codeSent").asBoolean()).as("no second email within a minute").isFalse();
        assertThat(again.get("activationId").asText()).isEqualTo(activationId);
        Browser.Res tooSoon = adeola.post("/api/v1/auth/student/activation/resend", Map.of("activationId", activationId));
        assertThat(tooSoon.status()).isEqualTo(429);
        assertThat(tooSoon.json().get("error").asText()).isEqualTo("resend_too_soon");

        String code = codeFrom(adeolaEmail, "activation code");
        assertThat(System.currentTimeMillis() - asked).as("the code arrives within 60 seconds").isLessThan(60_000);

        Browser.Res wrong = adeola.post("/api/v1/auth/student/activation/verify", Map.of("activationId", activationId, "code", code.equals("000000") ? "111111" : "000000"));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.json().get("error").asText()).isEqualTo("wrong_code");
        assertThat(wrong.json().get("attemptsLeft").asInt()).isEqualTo(2);
        assertThat(wrong.json().get("message").asText()).isEqualTo("That code is not right. 2 tries left before we send a new one.");
        JsonNode verified = adeola.post("/api/v1/auth/student/activation/verify", Map.of("activationId", activationId, "code", code)).json();
        String token = verified.get("activationToken").asText();
        assertThat(verified.at("/student/fullName").asText()).isEqualTo("Adeola Okafor");
        assertThat(adeola.post("/api/v1/auth/student/activation/verify", Map.of("activationId", activationId, "code", code)).status())
                .as("a code works once").isEqualTo(410);

        // M-04: password rules.
        Browser.Res weak = adeola.post("/api/v1/auth/student/activation/password", Map.of("activationToken", token, "password", "short", "confirmPassword", "short"));
        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.json().at("/fields/password").asText()).isEqualTo("Use at least 8 characters.");
        assertThat(adeola.post("/api/v1/auth/student/activation/password", Map.of("activationToken", token, "password", "adeolaokafor", "confirmPassword", "adeolaokafor"))
                .json().at("/fields/password").asText()).isEqualTo("Add at least one number.");
        assertThat(adeola.post("/api/v1/auth/student/activation/password", Map.of("activationToken", token, "password", password, "confirmPassword", "Adeola2025"))
                .json().at("/fields/confirmPassword").asText()).isEqualTo("The two passwords do not match.");
        assertThat(adeola.post("/api/v1/auth/student/activation/password", Map.of("activationToken", token, "password", password, "confirmPassword", password)).status()).isEqualTo(204);

        // M-05: consent, then Home.
        assertThat(adeola.post("/api/v1/auth/student/activation/complete", Map.of("activationToken", token, "acceptPrivacyNotice", false)).json().get("error").asText())
                .isEqualTo("consent_required");
        Browser.Res done = adeola.post("/api/v1/auth/student/activation/complete", Map.of("activationToken", token, "acceptPrivacyNotice", true, "privacyNoticeVersion", "2026-11"));
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        assertThat(done.json().get("stage").asText()).isEqualTo("SIGNED_IN");
        assertThat(done.json().at("/roles/0/role").asText()).isEqualTo("STUDENT");
        assertThat(adeola.get("/api/v1/session").json().at("/user/fullName").asText()).isEqualTo("Adeola Okafor");
        JsonNode me = adeola.get("/api/v1/student-profile").json();
        assertThat(me.get("matricNumber").asText()).isEqualTo(adeolaMatric);
        assertThat(me.get("levelLabel").asText()).isEqualTo("200L");
        assertThat(me.at("/consent/version").asText()).isEqualTo("2026-11");
        assertThat(adeola.send("PATCH", "/api/v1/student-profile", Map.of("phone", "0803 412 4418"), "application/json").json().get("phone").asText()).isEqualTo("0803 412 4418");
        assertThat(adeola.send("PATCH", "/api/v1/student-profile", Map.of("phone", "12"), "application/json").status()).isEqualTo(400);
        assertThat(adeola.get("/api/v1/students").status()).as("students never reach staff pages").isEqualTo(403);
        assertThat(adeola.get("/api/v1/imports/students").status()).isEqualTo(403);
        assertThat(adeola.get("/api/v1/notifications/unread-count").status()).isEqualTo(200);

        // E-02: already active.
        Browser.Res active = student(wesleyHost).post("/api/v1/auth/student/activation/start", Map.of("login", adeolaMatric));
        assertThat(active.status()).isEqualTo(409);
        assertThat(active.json().get("error").asText()).isEqualTo("already_active");

        // S-04 sign out, M-08 sign in with matric number and with email.
        assertThat(adeola.post("/api/v1/auth/sign-out", Map.of()).status()).isEqualTo(204);
        assertThat(adeola.get("/api/v1/session").status()).isEqualTo(401);
        Browser.Res in = adeola.post("/api/v1/auth/student/sign-in", Map.of("login", "WU/ODL/24/0153", "password", password));
        assertThat(in.status()).as(in.body()).isEqualTo(200);
        assertThat(in.json().get("idleTimeoutSeconds").asInt()).isEqualTo(1800);
        Browser byEmail = student(wesleyHost);
        assertThat(byEmail.post("/api/v1/auth/student/sign-in", Map.of("login", adeolaEmail.toUpperCase(), "password", password, "keepSignedIn", true)).status()).isEqualTo(200);
        assertThat(byEmail.get("/api/v1/session").status()).isEqualTo(200);

        // W-22b: active, with her consent date.
        JsonNode rec = ngoziB.get("/api/v1/students/" + adeolaId).json();
        assertThat(rec.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(rec.at("/account/consent/at").asText()).isNotBlank();
        assertThat(rec.at("/account/activatedAt").asText()).isNotBlank();
        assertThat(rec.at("/account/lastSignInAt").asText()).isNotBlank();
        assertThat(rec.get("history").findValuesAsText("to")).containsSequence("IMPORTED", "INVITED", "ACTIVE");
        assertThat(rec.at("/importedFrom/fileName").asText()).isEqualTo("portal_export.csv");
        assertThat(ngoziB.get("/api/v1/students?status=ACTIVE").json().get("total").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 5. lockout and reset

    @Test
    @Order(4)
    void lockedAfterFiveWrongPasswordsThenResetByCode() throws Exception {
        Browser other = student(wesleyHost);
        assertThat(other.post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", password)).status()).isEqualTo(200);

        Browser b = student(wesleyHost);
        for (int left = 4; left >= 1; left--) {
            Browser.Res r = b.post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", "Wrong" + left + "pass"));
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.json().get("attemptsLeft").asInt()).isEqualTo(left);
            if (left == 3) assertThat(r.json().get("message").asText()).isEqualTo("Wrong password. 3 tries left before sign-in pauses for 15 minutes.");
        }
        Browser.Res fifth = b.post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", "Wrong5pass"));
        assertThat(fifth.status()).as("the fifth wrong password pauses sign-in").isEqualTo(423);
        Browser.Res locked = b.post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", password));
        assertThat(locked.status()).as("E-05: the right password while paused").isEqualTo(423);
        assertThat(locked.json().get("retryInSeconds").asInt()).isBetween(800, 900);
        assertThat(locked.json().get("message").asText()).startsWith("Too many attempts. Sign-in is paused for 15 minutes.");
        assertThat(ngoziB.get("/api/v1/students/" + adeolaId).json().at("/account/lockedUntil").asText()).isNotBlank();

        // M-09 … M-11: reset by emailed code; other sessions end; the pause is lifted.
        JsonNode start = b.post("/api/v1/auth/student/password-reset/start", Map.of("login", adeolaMatric)).json();
        String resetId = start.get("resetId").asText();
        assertThat(start.get("codeSent").asBoolean()).isTrue();
        String code = codeFrom(adeolaEmail, "password reset code");
        String token = b.post("/api/v1/auth/student/password-reset/verify", Map.of("resetId", resetId, "code", code)).json().get("resetToken").asText();
        Browser.Res weak = b.post("/api/v1/auth/student/password-reset/complete", Map.of("resetToken", token, "password", "12345678", "confirmPassword", "12345678"));
        assertThat(weak.json().at("/fields/password").asText()).isEqualTo("Add at least one letter.");
        String newPassword = "NewPass2026";
        Browser.Res reset = b.post("/api/v1/auth/student/password-reset/complete", Map.of("resetToken", token, "password", newPassword, "confirmPassword", newPassword));
        assertThat(reset.status()).as(reset.body()).isEqualTo(200);
        assertThat(reset.json().get("stage").asText()).isEqualTo("SIGNED_IN");
        assertThat(other.get("/api/v1/session").status()).as("other sessions end").isEqualTo(401);
        assertThat(adeola.get("/api/v1/session").status()).isEqualTo(401);
        assertThat(student(wesleyHost).post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", password)).status()).isEqualTo(401);
        adeola = student(wesleyHost);
        assertThat(adeola.post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", newPassword)).status()).isEqualTo(200);
        password = newPassword;

        // W-22b "Send her a reset code by email": the code and a link to the reset screen.
        assertThat(ngoziB.post("/api/v1/students/" + adeolaId + "/password-reset", Map.of()).status()).isEqualTo(204);
        JsonNode office = email(adeolaEmail, "Reset your Wesley University password", Set.of(), 30);
        assertThat(office.get("body").asText()).contains("/reset-password?login=WU%2FODL%2F24%2F0153&sent=1");
        JsonNode reuse = student(wesleyHost).post("/api/v1/auth/student/password-reset/start", Map.of("login", adeolaMatric, "codeAlreadySent", true)).json();
        assertThat(reuse.get("codeSent").asBoolean()).as("the office's code is reused").isFalse();
    }

    // ------------------------------------------------------------------ suspension

    @Test
    @Order(5)
    void suspendedStudentIsSignedOutAndRefused() {
        assertThat(adeola.get("/api/v1/session").status()).isEqualTo(200);
        Browser.Res s = ngoziB.post("/api/v1/students/" + adeolaId + "/suspend", Map.of("reason", "Fees query"));
        assertThat(s.status()).as(s.body()).isEqualTo(200);
        assertThat(s.json().get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(adeola.get("/api/v1/session").status()).as("signed out at once").isEqualTo(401);
        Browser.Res refused = student(wesleyHost).post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", password));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.json().get("message").asText()).isEqualTo("Your account is suspended. Please contact the ODeL office at Wesley University to restore access. Your work is safe.");
        assertThat(student(wesleyHost).post("/api/v1/auth/student/password-reset/start", Map.of("login", adeolaMatric)).status()).isEqualTo(403);
        assertThat(ngoziB.post("/api/v1/students/" + adeolaId + "/suspend", Map.of()).status()).isEqualTo(409);
        assertThat(ngoziB.get("/api/v1/students?status=SUSPENDED").json().get("total").asInt()).isEqualTo(1);

        JsonNode back = ngoziB.post("/api/v1/students/" + adeolaId + "/reinstate", Map.of()).json();
        assertThat(back.get("status").asText()).isEqualTo("ACTIVE");
        adeola = student(wesleyHost);
        assertThat(adeola.post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", password)).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ edge cases

    @Test
    @Order(6)
    void edgeCases() throws Exception {
        // E-01 not found.
        Browser.Res nf = student(wesleyHost).post("/api/v1/auth/student/activation/start", Map.of("login", "WU/ODL/24/0195"));
        assertThat(nf.status()).isEqualTo(404);
        assertThat(nf.json().get("error").asText()).isEqualTo("matric_not_found");
        assertThat(nf.json().get("message").asText()).isEqualTo("We could not find this number. Check it on your admission letter, or contact your study centre.");
        assertThat(student(wesleyHost).post("/api/v1/auth/student/sign-in", Map.of("login", "WU/ODL/24/0195", "password", "x")).status()).isEqualTo(404);

        // E-03 no email on record.
        Browser.Res noEmail = student(wesleyHost).post("/api/v1/auth/student/activation/start", Map.of("login", "WU/ODL/25/0042"));
        assertThat(noEmail.status()).isEqualTo(422);
        assertThat(noEmail.json().get("error").asText()).isEqualTo("no_email");
        assertThat(noEmail.json().at("/centre/name").asText()).isEqualTo("Abuja Study Centre");
        assertThat(noEmail.json().at("/centre/phone").asText()).as("E-03 shows the centre's phone from academic-service").isEqualTo(ABUJA_PHONE);
        assertThat(ngoziB.post("/api/v1/students/" + musaId + "/resend-activation", Map.of()).status()).isEqualTo(422);

        // Not activated yet: sign-in and reset point to activation.
        assertThat(student(wesleyHost).post("/api/v1/auth/student/sign-in", Map.of("login", "WU/ODL/24/0688", "password", "Whatever1")).json().get("error").asText())
                .isEqualTo("not_activated");
        assertThat(student(wesleyHost).post("/api/v1/auth/student/password-reset/start", Map.of("login", "WU/ODL/24/0688")).status()).isEqualTo(409);

        // Wrong code three times: a new code is sent.
        Browser bayo = student(wesleyHost);
        String bayoEmail = "bayo.adewale@" + studentMail;
        String id = bayo.post("/api/v1/auth/student/activation/start", Map.of("login", "WU/ODL/24/0688")).json().get("activationId").asText();
        String first = codeFrom(bayoEmail, "activation code");
        String wrongCode = first.equals("000000") ? "111111" : "000000";
        assertThat(bayo.post("/api/v1/auth/student/activation/verify", Map.of("activationId", id, "code", wrongCode)).json().get("attemptsLeft").asInt()).isEqualTo(2);
        assertThat(bayo.post("/api/v1/auth/student/activation/verify", Map.of("activationId", id, "code", wrongCode)).json().get("attemptsLeft").asInt()).isEqualTo(1);
        Browser.Res third = bayo.post("/api/v1/auth/student/activation/verify", Map.of("activationId", id, "code", wrongCode));
        assertThat(third.json().get("error").asText()).isIn("new_code_sent", "code_expired");
        assertThat(bayo.post("/api/v1/auth/student/activation/verify", Map.of("activationId", id, "code", first)).status()).as("the used-up code no longer works").isIn(400, 410);
        if (third.json().get("error").asText().equals("new_code_sent")) {
            String second = codeFrom(bayoEmail, "activation code");
            assertThat(bayo.post("/api/v1/auth/student/activation/verify", Map.of("activationId", id, "code", second)).status()).isEqualTo(200);
        }
        assertThat(bayo.post("/api/v1/auth/student/activation/verify", Map.of("activationId", "made-up", "code", "123456")).json().get("error").asText()).isEqualTo("expired");

        // W-22c add one student: checks as the import does.
        Map<String, Object> add = new LinkedHashMap<>(Map.of("surname", "Eze", "firstName", "Grace", "matricNumber", "WU/ODL/25/0107", "programmeCode", "BSC-IFT-ODL",
                "level", 100, "centreCode", "AKR", "email", "grace.eze@" + studentMail, "phone", "0806 555 0191", "sendWelcome", true));
        Map<String, Object> dup = new LinkedHashMap<>(add);
        dup.put("matricNumber", adeolaMatric);
        assertThat(ngoziB.post("/api/v1/students", dup).json().get("error").asText()).isEqualTo("matric_taken");
        Map<String, Object> badCentre = new LinkedHashMap<>(add);
        badCentre.put("centreCode", "Ikeja");
        Browser.Res bc = ngoziB.post("/api/v1/students", badCentre);
        assertThat(bc.status()).isEqualTo(400);
        assertThat(bc.json().at("/fields/centreCode").asText()).contains("Did you mean \"Lagos (Ikeja)\"");
        Map<String, Object> staffEmail = new LinkedHashMap<>(add);
        staffEmail.put("email", ngozi);
        assertThat(ngoziB.post("/api/v1/students", staffEmail).json().get("error").asText()).isEqualTo("email_taken");
        Browser.Res added = ngoziB.post("/api/v1/students", add);
        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(added.json().get("status").asText()).isEqualTo("IMPORTED");
        assertThat(email("grace.eze@" + studentMail, "Welcome to Wesley University ODeL", Set.of(), 30)).as("Add and send welcome").isNotNull();
        String graceId = added.json().get("id").asText();
        Map<String, Object> noMail = new LinkedHashMap<>(add);
        noMail.put("matricNumber", "WU/ODL/25/0108");
        noMail.remove("email");
        noMail.put("firstName", "Remi");
        assertThat(ngoziB.post("/api/v1/students", noMail).status()).as("a student with no email can be added").isEqualTo(201);

        // W-22b edit, with audit; a centre change is a move (B2, A-06) and needs a reason.
        Browser.Res noReason = ngoziB.send("PATCH", "/api/v1/students/" + graceId, Map.of("centreCode", "LAG"), "application/json");
        assertThat(noReason.json().at("/fields/centreChangeReason").asText()).isEqualTo("Say why the student is moving centre.");
        Browser.Res edited = ngoziB.send("PATCH", "/api/v1/students/" + graceId,
                Map.of("centreCode", "LAG", "centreChangeReason", "Lives in Lagos now", "phone", "08065550192"), "application/json");
        assertThat(edited.status()).as(edited.body()).isEqualTo(200);
        assertThat(edited.json().at("/centre/code").asText()).isEqualTo("LAG");
        assertThat(edited.json().get("phone").asText()).isEqualTo("0806 555 0192");
        assertThat(ngoziB.send("PATCH", "/api/v1/students/" + graceId, Map.of("phone", "123"), "application/json").json().at("/fields/phone").asText()).isNotBlank();
        assertThat(ngoziB.send("PATCH", "/api/v1/students/" + graceId, Map.of("matricNumber", adeolaMatric), "application/json").status()).isEqualTo(409);
        assertThat(ngoziB.send("PATCH", "/api/v1/students/" + graceId, Map.of("level", 700), "application/json").status()).isEqualTo(400);

        // Account actions that do not fit the student's state.
        assertThat(ngoziB.post("/api/v1/students/" + adeolaId + "/resend-activation", Map.of()).json().get("error").asText()).isEqualTo("already_active");
        assertThat(ngoziB.post("/api/v1/students/" + graceId + "/password-reset", Map.of()).json().get("error").asText()).isEqualTo("not_activated");
        assertThat(ngoziB.post("/api/v1/students/" + graceId + "/resend-activation", Map.of()).status()).isEqualTo(204);

        // The import's file-level problems.
        String noHeader = upload(ngoziB, "bad.csv", "a,b,c\n1,2,3\n");
        assertThat(ngoziB.post("/api/v1/imports/students/validate", Map.of("key", noHeader, "fileName", "bad.csv")).json().get("error").asText()).isEqualTo("no_header");
        String missingCols = upload(ngoziB, "cols.csv", "matric_number,surname,first_name\nX/1,A,B\n");
        assertThat(ngoziB.post("/api/v1/imports/students/validate", Map.of("key", missingCols, "fileName", "cols.csv")).json().at("/fields/study_centre").asText()).isNotBlank();
        assertThat(ngoziB.post("/api/v1/imports/students/validate", Map.of("key", "imports/" + wesleyId + "/nothing.csv", "fileName", "x.csv")).json().get("error").asText())
                .isEqualTo("file_missing");
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    @Order(7)
    void crestfieldCannotReachWesleysStudents() {
        String w = "/api/v1/students/" + adeolaId;
        assertThat(yemiB.get(w).status()).isEqualTo(404);
        assertThat(yemiB.send("PATCH", w, Map.of("phone", "08030000000"), "application/json").status()).isEqualTo(404);
        assertThat(yemiB.post(w + "/suspend", Map.of()).status()).isEqualTo(404);
        assertThat(yemiB.post(w + "/reinstate", Map.of()).status()).isEqualTo(404);
        assertThat(yemiB.post(w + "/resend-activation", Map.of()).status()).isEqualTo(404);
        assertThat(yemiB.post(w + "/password-reset", Map.of()).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/students").json().get("total").asInt()).isZero();
        assertThat(yemiB.get("/api/v1/students?q=okafor").json().get("total").asInt()).isZero();
        String i = "/api/v1/imports/students/" + importId;
        assertThat(yemiB.get(i).status()).isEqualTo(404);
        assertThat(yemiB.get(i + "/problems").status()).isEqualTo(404);
        assertThat(yemiB.get(i + "/problems.csv").status()).isEqualTo(404);
        assertThat(yemiB.post(i + "/confirm", Map.of()).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/imports/students").json().get("total").asInt()).isZero();
        assertThat(yemiB.post("/api/v1/imports/students/validate", Map.of("key", fileKey, "fileName", "stolen.csv")).json().get("error").asText()).isEqualTo("file_missing");
        String s = "/api/v1/students/welcome/sends/" + sendId;
        assertThat(yemiB.get(s).status()).isEqualTo(404);
        assertThat(yemiB.get(s + "/deliveries").status()).isEqualTo(404);
        assertThat(yemiB.get(s + "/failed.csv").status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/students/welcome").json().at("/template/custom").asBoolean()).as("Wesley's wording stays Wesley's").isFalse();
        assertThat(yemiB.get("/api/v1/students/welcome").json().at("/audience/notActivated").asInt()).isZero();

        // Wesley's students do not exist on Crestfield's portal, and their session does not carry over.
        Browser onCrest = student(crestHost);
        assertThat(onCrest.post("/api/v1/auth/student/activation/start", Map.of("login", adeolaMatric)).status()).isEqualTo(404);
        assertThat(onCrest.post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", password)).status()).isEqualTo(404);
        assertThat(onCrest.post("/api/v1/auth/student/password-reset/start", Map.of("login", adeolaEmail)).status()).isEqualTo(404);
        Browser replay = new Browser(gw, crestHost);
        replay.cookies.putAll(adeola.cookies);
        assertThat(replay.get("/api/v1/student-profile").status()).isEqualTo(401);
        // Nor on the provider console.
        assertThat(student(console).post("/api/v1/auth/student/sign-in", Map.of("login", adeolaMatric, "password", password)).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 3,504 rows in under a minute

    @Test
    @Order(8)
    void theStoryFileOf3504RowsImportsInUnderAMinute() {
        long t0 = System.currentTimeMillis();
        String key = upload(yemiB, "portal_export_2026-12-04.csv", bigFile("crest-" + run + ".test"));
        JsonNode report = yemiB.post("/api/v1/imports/students/validate", Map.of("key", key, "fileName", "portal_export_2026-12-04.csv")).json();
        long checked = System.currentTimeMillis();
        assertThat(report.get("totalRows").asInt()).isEqualTo(3504);
        assertThat(report.at("/ready/count").asInt()).isEqualTo(3478);
        assertThat(report.at("/errors/count").asInt()).isEqualTo(26);
        Browser.Res confirmed = yemiB.post("/api/v1/imports/students/" + report.get("importId").asText() + "/confirm", Map.of());
        long done = System.currentTimeMillis();
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.json().get("added").asInt()).isEqualTo(3478);
        System.out.printf("3,504-row import: upload + check %d ms, confirm %d ms, total %d ms%n", checked - t0, done - checked, done - t0);
        assertThat(done - t0).as("3,504 rows import in under a minute").isLessThan(60_000);
        assertThat(yemiB.get("/api/v1/students").json().get("total").asInt()).isEqualTo(3478);
        assertThat(ngoziB.get("/api/v1/students").json().get("total").asInt()).as("Wesley is untouched").isEqualTo(32);
    }

    // ------------------------------------------------------------------ the audit log

    @Test
    @Order(9)
    void theAuditLogShowsTheImportTheSendAndTheActivation() {
        List<String> a = actions(wesleyId);
        assertThat(a).containsSubsequence("STUDENTS_IMPORTED", "WELCOME_TEMPLATE_UPDATED", "WELCOME_TEST_SENT", "WELCOME_SENT",
                "STUDENT_ACTIVATION_CODE_SENT", "STUDENT_ACTIVATED", "STUDENT_ACTIVATED_CONSENT", "STUDENT_SIGN_IN", "SIGN_OUT", "STUDENT_SIGN_IN");
        assertThat(a).contains("STUDENT_SIGN_IN_LOCKED", "STUDENT_PASSWORD_RESET_CODE_SENT", "STUDENT_PASSWORD_RESET", "STUDENT_SUSPENDED",
                "STUDENT_REINSTATED", "STUDENT_ADDED", "STUDENT_UPDATED", "STUDENT_CENTRE_CHANGED", "STUDENT_PASSWORD_RESET_SENT",
                "STUDENT_ACTIVATION_RESENT", "STUDENT_SIGN_IN_FAILED", "STUDENT_CODE_FAILED");
        assertThat(actions(crestId)).contains("STUDENTS_IMPORTED").doesNotContain("STUDENT_ACTIVATED");
    }
}
