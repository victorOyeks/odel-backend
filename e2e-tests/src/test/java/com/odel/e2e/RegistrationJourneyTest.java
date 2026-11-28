package com.odel.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 course registration end-to-end (Build plan B2 steps 4–6 and the registration edge cases), through the api-gateway of a
 * running stack with the dev profile. A fresh university: the ODeL admin sets up 2026/2027 (or whatever session holds today),
 * B.Sc. Computer Science 200L first-semester courses, Akure and Lagos (Ikeja) centres with Mrs Bisi Ade and Mr Tayo Lawal as
 * coordinators, and the students. The fee answer is set per student through the dev endpoint (payment-service not needed).
 *
 * Journey: registration not open (R-01); not paid (R-08); Adeola registers 7 courses, 18 units, with CSC 103 pinned as a
 * carry-over, and downloads her slip; over 20 and under 15 refused; a late request approved and one declined; a course change
 * approved by Mrs Ade and visible to Adeola (R-09d); A-07 lists her with 7 courses and 18 units; the Lagos coordinator cannot
 * see or decide Akure's requests; moving Adeola to Lagos emails three people, keeps history and hands her open request over;
 * tenant isolation for every new endpoint.
 *
 * Run: ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test -Dtest=RegistrationJourneyTest
 */
@EnabledIfEnvironmentVariable(named = "ODEL_E2E_GATEWAY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistrationJourneyTest {
    final String gw = System.getenv("ODEL_E2E_GATEWAY");
    final String base = Optional.ofNullable(System.getenv("ODEL_E2E_BASE_DOMAIN")).orElse("localhost:3000");
    final String console = Optional.ofNullable(System.getenv("ODEL_E2E_CONSOLE_HOST")).orElse("console." + base);
    final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36) + Integer.toString(new Random().nextInt(1296), 36);
    final String wesleySlug = "wes-reg-" + run, crestSlug = "crest-reg-" + run;
    final String wesleyHost = wesleySlug + "." + base, crestHost = crestSlug + "." + base;
    final String wesleyDomain = "wes-reg-" + run + ".edu.ng", crestDomain = "crest-reg-" + run + ".edu.ng";
    final String ngozi = "ngozi.adebayo@" + wesleyDomain, ade = "bisi.ade@" + wesleyDomain, lawal = "tayo.lawal@" + wesleyDomain, yemi = "yemi.adeoye@" + crestDomain;
    final String studentMail = "students-reg-" + run + ".test";
    final String adeolaEmail = "adeola.okafor@" + studentMail, bayoEmail = "bayo.adewale@" + studentMail, chidiEmail = "chidi.nwosu@" + studentMail,
            emekaEmail = "emeka.obi@" + studentMail;
    final Set<String> usedCodes = new HashSet<>();
    final LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
    final int firstYear = today.getMonthValue() >= 8 ? today.getYear() : today.getYear() - 1;
    final String sessionName = firstYear + "/" + (firstYear + 1);
    final String slipPrefix = "REG-" + String.valueOf(firstYear).substring(2) + "-1-";

    Browser chuka, ngoziB, adeB, lawalB, yemiB, adeola, bayo, chidi;
    String wesleyId, sessionId, semesterId, programmeId, akureId, lagosId, adeId, lawalId;
    String adeolaId, bayoId, chidiId, emekaId;
    final Map<String, String> course = new HashMap<>();
    String registrationId, verificationCode, bayoLateId, chidiLateId, changeId, secondChangeId, carryOverId;

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

    String createUniversity(String name, String slug, String domain, String admin, String adminEmail) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", name + " (" + slug + ")");   // full names are unique across the platform
        u.put("shortName", name.split(" ")[0] + " ODeL");
        u.put("slug", slug);
        u.put("googleDomain", domain);
        u.put("firstAdmin", Map.of("fullName", admin, "email", adminEmail));
        u.put("branding", Map.of("primaryColor", "#1A2368", "accentColor", "#C27808"));
        Browser.Res created = chuka.post("/api/v1/platform/tenants", u);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.json().get("id").asText();
    }

    JsonNode email(String to, String subjectPart, int seconds) throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            JsonNode list = chuka.get("/api/v1/dev/emails?to=" + URLEncoder.encode(to, StandardCharsets.UTF_8)).json();
            for (JsonNode e : list) {
                if (e.get("subject").asText().contains(subjectPart) && !e.get("status").asText().equals("QUEUED")) return e;
            }
            Thread.sleep(300);
        }
        return null;
    }

    static String created(Browser.Res r) {
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().get("id").asText();
    }

    static JsonNode ok(Browser.Res r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.body() == null || r.body().isBlank() ? null : r.json();
    }

    Browser.Res put(Browser b, String path, Object body) {
        return b.send("PUT", path, body, "application/json");
    }

    /** The semesters with the first one's registration window as given (days from today; null late = none). */
    void window(int opens, int closes, Integer late) {
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("opensOn", today.plusDays(opens).toString());
        reg.put("closesOn", today.plusDays(closes).toString());
        if (late != null) reg.put("lateClosesOn", today.plusDays(late).toString());
        LocalDate s1 = today.minusDays(60), e1 = today.plusDays(60), s2 = e1.plusDays(10), e2 = s2.plusDays(120);
        Map<String, Object> body = Map.of("name", sessionName, "makeActive", true, "semesters", List.of(
                Map.of("number", 1, "startsOn", s1.toString(), "endsOn", e1.toString(), "registration", reg),
                Map.of("number", 2, "startsOn", s2.toString(), "endsOn", e2.toString())));
        if (sessionId == null) {
            JsonNode s = ok(ngoziB.post("/api/v1/academic/sessions", body), 201);
            sessionId = s.get("id").asText();
            semesterId = s.at("/semesters/0/id").asText();
            assertThat(s.get("status").asText()).isEqualTo("ACTIVE");
        } else {
            ok(put(ngoziB, "/api/v1/academic/sessions/" + sessionId, body), 200);
        }
    }

    void fee(String studentId, String status) {
        ok(put(ngoziB, "/api/v1/dev/registration/fee-status", Map.of("studentId", studentId, "semesterId", semesterId, "status", status)), 204);
    }

    String addStudent(String surname, String first, String matric, String centre, String email) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("surname", surname);
        s.put("firstName", first);
        s.put("matricNumber", matric);
        s.put("programmeCode", "BSC-CSC-ODL");
        s.put("level", 200);
        s.put("centreCode", centre);
        s.put("email", email);
        s.put("sendWelcome", false);
        return created(ngoziB.post("/api/v1/students", s));
    }

    final Set<String> seenCodeMails = new HashSet<>();

    /** Activates a student's account by matric number and emailed code; returns their signed-in browser. */
    Browser activate(String matric, String email) throws InterruptedException {
        Browser b = new Browser(gw, wesleyHost);
        b.get("/api/v1/public/context");
        Browser.Res start = b.post("/api/v1/auth/student/activation/start", Map.of("login", matric));
        assertThat(start.status()).as(start.body()).isEqualTo(200);
        String activationId = start.json().get("activationId").asText();
        String code = null;
        long until = System.currentTimeMillis() + 60_000;
        while (code == null && System.currentTimeMillis() < until) {
            for (JsonNode e : chuka.get("/api/v1/dev/emails?to=" + URLEncoder.encode(email, StandardCharsets.UTF_8)).json()) {
                if (e.get("subject").asText().contains("activation code") && !e.get("status").asText().equals("QUEUED") && seenCodeMails.add(e.get("body").asText())) {
                    Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(e.get("body").asText());
                    if (m.find()) code = m.group(1);
                    break;
                }
            }
            if (code == null) Thread.sleep(300);
        }
        assertThat(code).as("activation code for " + email).isNotNull();
        String token = ok(b.post("/api/v1/auth/student/activation/verify", Map.of("activationId", activationId, "code", code)), 200).get("activationToken").asText();
        ok(b.post("/api/v1/auth/student/activation/password", Map.of("activationToken", token, "password", "Student2026", "confirmPassword", "Student2026")), 204);
        ok(b.post("/api/v1/auth/student/activation/complete", Map.of("activationToken", token, "acceptPrivacyNotice", true, "privacyNoticeVersion", "2026-11")), 200);
        return b;
    }

    static List<String> codes(JsonNode courses) {
        List<String> out = new ArrayList<>();
        courses.forEach(c -> out.add(c.get("code").asText()));
        return out;
    }

    // ------------------------------------------------------------------ setup

    @BeforeAll
    void setUp() throws Exception {
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
        wesleyId = createUniversity("Wesley University", wesleySlug, wesleyDomain, "Ngozi Adebayo", ngozi);
        createUniversity("Crestfield University", crestSlug, crestDomain, "Yemi Adeoye", yemi);
        ngoziB = new Browser(gw, wesleyHost);
        firstSignIn(ngoziB, ngozi);
        yemiB = new Browser(gw, crestHost);
        firstSignIn(yemiB, yemi);
        assertThat(ngoziB.get("/api/v1/registration/me").status()).as("registration-service must be running (staff get 403 here)").isEqualTo(403);

        // Structure, centres, the 200L first-semester catalogue and CSC 103 (100L) for the carry-over.
        String faculty = created(ngoziB.post("/api/v1/academic/faculties", Map.of("name", "Faculty of Science", "code", "SCI")));
        String dept = created(ngoziB.post("/api/v1/academic/departments", Map.of("facultyId", faculty, "name", "Computer Science", "code", "CSC")));
        programmeId = created(ngoziB.post("/api/v1/academic/programmes", Map.of("departmentId", dept, "name", "B.Sc. Computer Science", "code", "BSC-CSC-ODL",
                "degree", "B.Sc.", "durationYears", 4, "entryLevels", List.of(100, 200))));
        akureId = created(ngoziB.post("/api/v1/academic/centres", Map.of("name", "Akure Study Centre", "code", "AKR", "address", "1 Oba Adesida Road", "state", "Ondo", "phone", "0803 555 0117")));
        lagosId = created(ngoziB.post("/api/v1/academic/centres", Map.of("name", "Lagos (Ikeja)", "code", "LAG", "address", "2 Allen Avenue", "state", "Lagos", "phone", "0803 555 0142")));
        Object[][] catalogue = {
                {"CSC 201", "Data Structures and Algorithms", 3, 200, "COMPULSORY"}, {"CSC 203", "Computer Architecture", 3, 200, "COMPULSORY"},
                {"CSC 205", "Discrete Mathematics", 2, 200, "COMPULSORY"}, {"GST 201", "Entrepreneurship Studies", 2, 200, "COMPULSORY"},
                {"CSC 209", "Web Technologies", 2, 200, "ELECTIVE"}, {"MTH 211", "Linear Algebra I", 3, 200, "ELECTIVE"},
                {"CSC 211", "Data Communication", 3, 200, "ELECTIVE"}, {"CSC 103", "Introduction to Programming", 3, 100, "COMPULSORY"}};
        for (Object[] c : catalogue) {
            course.put((String) c[0], created(ngoziB.post("/api/v1/academic/courses", Map.of("code", c[0], "title", c[1], "units", c[2], "level", c[3], "semester", 1,
                    "offeredTo", List.of(Map.of("programmeId", programmeId, "kind", c[4]))))));
        }
        window(5, 10, 15);   // not open yet

        // Coordinators: Mrs Bisi Ade (Akure) and Mr Tayo Lawal (Lagos), signed in once.
        adeId = created(ngoziB.post("/api/v1/people", Map.of("fullName", "Mrs Bisi Ade", "email", ade,
                "roles", List.of(Map.of("role", "CENTRE_COORDINATOR", "scopeType", "CENTRE", "scopeId", akureId)))));
        lawalId = created(ngoziB.post("/api/v1/people", Map.of("fullName", "Mr Tayo Lawal", "email", lawal,
                "roles", List.of(Map.of("role", "CENTRE_COORDINATOR", "scopeType", "CENTRE", "scopeId", lagosId)))));
        ok(put(ngoziB, "/api/v1/academic/staffing/centres/" + akureId, Map.of("coordinatorId", adeId, "notify", false)), 200);
        ok(put(ngoziB, "/api/v1/academic/staffing/centres/" + lagosId, Map.of("coordinatorId", lawalId, "notify", false)), 200);
        adeB = new Browser(gw, wesleyHost);
        firstSignIn(adeB, ade);
        lawalB = new Browser(gw, wesleyHost);
        firstSignIn(lawalB, lawal);

        // Students (B.Sc. Computer Science 200L): three at Akure, one at Lagos.
        adeolaId = addStudent("Okafor", "Adeola", "WU/ODL/24/0153", "AKR", adeolaEmail);
        bayoId = addStudent("Adewale", "Bayo", "WU/ODL/24/0688", "AKR", bayoEmail);
        chidiId = addStudent("Nwosu", "Chidi", "WU/ODL/24/0154", "AKR", chidiEmail);
        emekaId = addStudent("Obi", "Emeka", "WU/ODL/24/0219", "LAG", emekaEmail);
        adeola = activate("WU/ODL/24/0153", adeolaEmail);
        bayo = activate("WU/ODL/24/0688", bayoEmail);
        chidi = activate("WU/ODL/24/0154", chidiEmail);
    }

    // ------------------------------------------------------------------ R-01 not open, R-08 not paid

    @Test
    @Order(1)
    void registrationNotOpenYet() {
        JsonNode me = ok(adeola.get("/api/v1/registration/me"), 200);
        assertThat(me.at("/window/state").asText()).isEqualTo("NOT_OPEN");
        assertThat(me.get("canRegister").asBoolean()).isFalse();
        assertThat(me.at("/blocked/code").asText()).isEqualTo("not_open");
        assertThat(me.at("/blocked/message").asText()).startsWith("Registration opens ");
        assertThat(me.at("/semester/label").asText()).isEqualTo(sessionName + " · 1st semester");
        assertThat(me.at("/student/matricNumber").asText()).isEqualTo("WU/ODL/24/0153");
        Browser.Res submit = adeola.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(), "confirm", true));
        assertThat(submit.status()).isEqualTo(409);
        assertThat(submit.json().get("error").asText()).isEqualTo("not_open");
    }

    @Test
    @Order(2)
    void notPaidShowsTheWayToPay() {
        window(-1, 5, 10);
        // CSC 103 failed last session: recorded by the office, pinned on her registration.
        carryOverId = created(ngoziB.post("/api/v1/registration/carry-overs", Map.of("studentId", adeolaId, "courseCode", "csc103", "fromSession", (firstYear - 1) + "/" + firstYear)));
        assertThat(ngoziB.post("/api/v1/registration/carry-overs", Map.of("studentId", adeolaId, "courseCode", "CSC 103")).json().get("error").asText()).isEqualTo("already_open");
        assertThat(ngoziB.post("/api/v1/registration/carry-overs", Map.of("studentId", adeolaId, "courseCode", "XYZ 999")).json().at("/fields/courseCode").asText())
                .isEqualTo("XYZ 999 is not in the catalogue.");
        fee(adeolaId, "NOT_PAID");
        JsonNode me = ok(adeola.get("/api/v1/registration/me"), 200);
        assertThat(me.at("/window/state").asText()).isEqualTo("OPEN");
        assertThat(me.at("/fee/status").asText()).isEqualTo("NOT_PAID");
        assertThat(me.at("/fee/label").asText()).isEqualTo("Platform fee · " + sessionName + " · 1st semester");
        assertThat(me.at("/blocked/code").asText()).isEqualTo("fee_not_paid");
        assertThat(me.at("/blocked/message").asText()).contains("₦15,000").contains(sessionName);
        Browser.Res submit = adeola.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(course.get("CSC 209"), course.get("MTH 211")), "confirm", true));
        assertThat(submit.status()).isEqualTo(402);
        assertThat(submit.json().get("error").asText()).isEqualTo("fee_not_paid");
        assertThat(submit.json().get("payPath").asText()).isEqualTo("/payments");
        fee(adeolaId, "CONFIRMING");
        assertThat(ok(adeola.get("/api/v1/registration/me"), 200).at("/blocked/code").asText()).isEqualTo("fee_confirming");
    }

    // ------------------------------------------------------------------ R-02 … R-05: 7 courses, 18 units, the slip

    @Test
    @Order(3)
    void adeolaRegistersSevenCoursesEighteenUnits() throws Exception {
        fee(adeolaId, "PAID");
        JsonNode me = ok(adeola.get("/api/v1/registration/me"), 200);
        assertThat(me.get("canRegister").asBoolean()).as(me.toString()).isTrue();
        assertThat(me.at("/limits/maxUnits").asInt()).isEqualTo(20);
        assertThat(me.at("/limits/minUnits").asInt()).isEqualTo(15);

        JsonNode choice = ok(adeola.get("/api/v1/registration/me/courses"), 200);
        assertThat(codes(choice.get("compulsory"))).containsExactly("CSC 201", "CSC 203", "CSC 205", "GST 201");
        assertThat(codes(choice.get("carryOvers"))).containsExactly("CSC 103");
        assertThat(choice.at("/carryOvers/0/locked").asBoolean()).isTrue();
        assertThat(codes(choice.get("electives"))).containsExactlyInAnyOrder("CSC 209", "MTH 211", "CSC 211");
        assertThat(choice.get("lockedUnits").asInt()).isEqualTo(13);
        assertThat(choice.get("subtitle").asText()).startsWith("B.Sc. Computer Science · 200L · closes ");

        // R-03: over 20; R-03b: under 15.
        List<String> all3 = List.of(course.get("CSC 209"), course.get("MTH 211"), course.get("CSC 211"));
        JsonNode over = ok(adeola.post("/api/v1/registration/me/check", Map.of("electiveIds", all3)), 200);
        assertThat(over.get("ok").asBoolean()).isFalse();
        assertThat(over.get("totalUnits").asInt()).isEqualTo(21);
        assertThat(over.at("/problems/0/code").asText()).isEqualTo("over_max");
        Browser.Res overSubmit = adeola.post("/api/v1/registration/me/submit", Map.of("electiveIds", all3, "confirm", true));
        assertThat(overSubmit.status()).isEqualTo(400);
        assertThat(overSubmit.json().get("message").asText()).isEqualTo("That is 21 units. The most you can take is 20: remove an elective.");
        Browser.Res under = adeola.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(), "confirm", true));
        assertThat(under.status()).isEqualTo(400);
        assertThat(under.json().at("/problems/0/code").asText()).isEqualTo("under_min");
        assertThat(under.json().get("message").asText()).isEqualTo("That is 13 units, 2 units under the minimum of 15. Add an elective.");
        assertThat(adeola.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(course.get("CSC 209"), course.get("MTH 211")))).json()
                .get("error").asText()).isEqualTo("confirm_required");

        JsonNode check = ok(adeola.post("/api/v1/registration/me/check", Map.of("electiveIds", List.of(course.get("CSC 209"), course.get("MTH 211")))), 200);
        assertThat(check.get("ok").asBoolean()).isTrue();
        assertThat(check.get("counter").asText()).isEqualTo("18 · min 15 · max 20");

        JsonNode reg = ok(adeola.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(course.get("CSC 209"), course.get("MTH 211")), "confirm", true)), 201);
        registrationId = reg.get("id").asText();
        verificationCode = reg.get("verificationCode").asText();
        assertThat(reg.get("courseCount").asInt()).isEqualTo(7);
        assertThat(reg.get("totalUnits").asInt()).isEqualTo(18);
        assertThat(reg.get("slipNumber").asText()).isEqualTo(slipPrefix + "0153");
        assertThat(reg.get("slipVersion").asInt()).isEqualTo(1);
        assertThat(codes(reg.get("courses"))).containsExactly("CSC 201", "CSC 203", "CSC 205", "GST 201", "CSC 103", "CSC 209", "MTH 211");
        assertThat(reg.at("/courses/4/kindLabel").asText()).isEqualTo("Carry-over");
        assertThat(reg.get("canRequestChange").asBoolean()).isTrue();
        assertThat(reg.at("/history/0/action").asText()).isEqualTo("SUBMITTED");

        Browser.Res again = adeola.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(course.get("CSC 209"), course.get("MTH 211")), "confirm", true));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().get("error").asText()).isEqualTo("already_registered");
        assertThat(ok(adeola.get("/api/v1/registration/me"), 200).at("/registration/totalUnits").asInt()).isEqualTo(18);

        // R-05: the slip, and anyone can verify it.
        Browser.Res slip = adeola.get("/api/v1/registration/me/registrations/" + registrationId + "/slip.pdf");
        assertThat(slip.status()).isEqualTo(200);
        assertThat(slip.body()).startsWith("%PDF-");
        Browser anyone = new Browser(gw, wesleyHost);
        JsonNode verified = ok(anyone.get("/api/v1/public/registration/verify/" + verificationCode), 200);
        assertThat(verified.get("studentName").asText()).isEqualTo("Adeola Okafor");
        assertThat(verified.get("totalUnits").asInt()).isEqualTo(18);
        assertThat(anyone.get("/api/v1/public/registration/verify/ZZZZ-ZZZZ").status()).isEqualTo(404);
        assertThat(ok(adeola.get("/api/v1/registration/me/registrations"), 200).get("items").size()).isEqualTo(1);

        JsonNode mail = email(adeolaEmail, "You are registered for", 30);
        assertThat(mail).isNotNull();
        assertThat(mail.get("body").asText()).contains("7 courses, 18 units").contains(slipPrefix + "0153").contains("CSC 103");
        assertThat(ngoziB.send("DELETE", "/api/v1/registration/carry-overs/" + carryOverId, null, null).status()).as("on a registration").isEqualTo(409);
    }

    // ------------------------------------------------------------------ late registration: approved and declined

    @Test
    @Order(4)
    void lateRequestsApprovedAndDeclined() throws Exception {
        fee(bayoId, "PAID");
        fee(chidiId, "PAID");
        assertThat(bayo.post("/api/v1/registration/me/late-requests", Map.of("reasonCategory", "MEDICAL", "reason", "I was in hospital for ten days.")).json()
                .get("error").asText()).as("while the window is open").isEqualTo("not_late");
        window(-10, -1, 5);   // closed yesterday; late registration for five more days
        JsonNode me = ok(bayo.get("/api/v1/registration/me"), 200);
        assertThat(me.at("/window/state").asText()).isEqualTo("LATE");
        assertThat(me.at("/blocked/code").asText()).isEqualTo("late_request_needed");

        // C30: the discharge letter goes browser → storage, then with the request.
        JsonNode link = ok(bayo.post("/api/v1/registration/me/uploads", Map.of("fileName", "discharge-letter.jpg", "contentType", "image/jpeg", "size", 2048)), 200);
        assertThat(bayo.post("/api/v1/registration/me/uploads", Map.of("fileName", "letter.exe", "size", 10)).json().at("/fields/fileName").asText())
                .isEqualTo("Attach a JPG, PNG or PDF file.");
        assertThat(bayo.send("PUT", link.get("uploadUrl").asText(), "x".repeat(2048), "image/jpeg").status()).isEqualTo(200);
        Browser.Res badKey = bayo.post("/api/v1/registration/me/late-requests", Map.of("reasonCategory", "MEDICAL", "reason", "I was admitted to hospital.",
                "attachment", Map.of("key", link.get("key").asText().replace(bayoId, chidiId), "fileName", "x.jpg")));
        assertThat(badKey.json().at("/fields/attachment").asText()).isEqualTo("Upload the file again.");
        JsonNode req = ok(bayo.post("/api/v1/registration/me/late-requests", Map.of("reasonCategory", "MEDICAL",
                "reason", "I was admitted to hospital from 10 to 18 Feb. My discharge letter is attached.",
                "attachment", Map.of("key", link.get("key").asText(), "fileName", "discharge-letter.jpg", "contentType", "image/jpeg", "size", 2048))), 201);
        bayoLateId = req.get("id").asText();
        assertThat(req.get("status").asText()).isEqualTo("PENDING");
        assertThat(req.at("/attachment/fileName").asText()).isEqualTo("discharge-letter.jpg");
        assertThat(req.at("/attachment/sizeLabel").asText()).isEqualTo("2 KB");
        assertThat(req.at("/centre/name").asText()).isEqualTo("Akure Study Centre");
        assertThat(bayo.post("/api/v1/registration/me/late-requests", Map.of("reasonCategory", "MEDICAL", "reason", "Asking a second time please.")).json()
                .get("error").asText()).isEqualTo("request_pending");
        assertThat(ok(bayo.get("/api/v1/registration/me"), 200).at("/blocked/code").asText()).isEqualTo("late_request_pending");
        assertThat(email(ade, "late registration request from Bayo Adewale", 30)).as("Mrs Ade is told").isNotNull();

        chidiLateId = ok(chidi.post("/api/v1/registration/me/late-requests", Map.of("reasonCategory", "WORK", "reason", "I travelled for work and missed the window.")), 201)
                .get("id").asText();

        // Mrs Ade's queue (Akure); Mr Lawal (Lagos) sees neither and cannot decide.
        JsonNode queue = ok(adeB.get("/api/v1/registration/requests?kind=LATE"), 200);
        assertThat(queue.get("total").asInt()).isEqualTo(2);
        assertThat(queue.at("/counts/late").asInt()).isEqualTo(2);
        assertThat(ok(lawalB.get("/api/v1/registration/requests"), 200).get("total").asInt()).isZero();
        assertThat(ok(lawalB.get("/api/v1/registration/requests?centreId=" + akureId), 200).get("total").asInt()).isZero();
        assertThat(lawalB.get("/api/v1/registration/requests/" + bayoLateId).status()).isEqualTo(404);
        assertThat(lawalB.get("/api/v1/registration/requests/" + bayoLateId + "/attachment").status()).isEqualTo(404);
        assertThat(lawalB.post("/api/v1/registration/requests/" + bayoLateId + "/approve", Map.of()).status()).isEqualTo(404);
        assertThat(lawalB.post("/api/v1/registration/requests/" + bayoLateId + "/decline", Map.of("note", "Not your centre at all.")).status()).isEqualTo(404);
        assertThat(bayo.get("/api/v1/registration/requests").status()).as("students never reach the queue").isEqualTo(403);

        JsonNode detail = ok(adeB.get("/api/v1/registration/requests/" + bayoLateId), 200);
        assertThat(detail.at("/request/reasonCategoryLabel").asText()).isEqualTo("Medical");
        JsonNode file = ok(adeB.get("/api/v1/registration/requests/" + bayoLateId + "/attachment"), 200);
        assertThat(adeB.get(file.get("url").asText()).status()).isEqualTo(200);

        JsonNode approved = ok(adeB.post("/api/v1/registration/requests/" + bayoLateId + "/approve", Map.of()), 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.at("/decision/by").asText()).isEqualTo("Mrs Bisi Ade");
        assertThat(approved.get("registerBy").asText()).isEqualTo(today.plusDays(5).toString());
        assertThat(adeB.post("/api/v1/registration/requests/" + bayoLateId + "/approve", Map.of()).json().get("message").asText())
                .isEqualTo("This request was already approved by Mrs Bisi Ade.");
        assertThat(email(bayoEmail, "Your late registration was approved", 30)).isNotNull();

        JsonNode bayoMe = ok(bayo.get("/api/v1/registration/me"), 200);
        assertThat(bayoMe.get("canRegister").asBoolean()).as(bayoMe.toString()).isTrue();
        assertThat(bayoMe.at("/window/registerBy").asText()).isEqualTo(today.plusDays(5).toString());
        assertThat(bayoMe.at("/lateRequest/status").asText()).isEqualTo("APPROVED");
        JsonNode bayoReg = ok(bayo.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(course.get("CSC 211"), course.get("CSC 209")), "confirm", true)), 201);
        assertThat(bayoReg.get("late").asBoolean()).isTrue();
        assertThat(bayoReg.get("totalUnits").asInt()).isEqualTo(15);

        // Declined: a note is required; Chidi sees it and may ask again.
        assertThat(adeB.post("/api/v1/registration/requests/" + chidiLateId + "/decline", Map.of()).json().at("/fields/note").asText())
                .isEqualTo("Tell the student why (5 to 500 characters).");
        JsonNode declined = ok(adeB.post("/api/v1/registration/requests/" + chidiLateId + "/decline",
                Map.of("note", "The discharge letter photo is not readable. Please upload a clearer photo or PDF.")), 200);
        assertThat(declined.get("status").asText()).isEqualTo("DECLINED");
        JsonNode chidiMe = ok(chidi.get("/api/v1/registration/me"), 200);
        assertThat(chidiMe.at("/lateRequest/statusLabel").asText()).isEqualTo("Not approved");
        assertThat(chidiMe.at("/lateRequest/decision/note").asText()).startsWith("The discharge letter photo");
        assertThat(chidiMe.at("/blocked/code").asText()).isEqualTo("late_request_needed");
        JsonNode mail = email(chidiEmail, "Your late registration was not approved", 30);
        assertThat(mail.get("body").asText()).contains("Mrs Bisi Ade: The discharge letter photo is not readable.");
        assertThat(chidi.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(course.get("CSC 209"), course.get("MTH 211")), "confirm", true)).status())
                .isEqualTo(409);
    }

    // ------------------------------------------------------------------ R-09 … R-09d: a course change approved by Mrs Ade

    @Test
    @Order(5)
    void courseChangeApprovedByTheCoordinator() throws Exception {
        Browser.Res compulsory = adeola.post("/api/v1/registration/me/change-requests", Map.of("registrationId", registrationId,
                "dropCourseIds", List.of(course.get("CSC 201")), "addCourseIds", List.of(), "reason", "I would rather not take this course."));
        assertThat(compulsory.status()).isEqualTo(400);
        assertThat(compulsory.json().get("message").asText()).isEqualTo("CSC 201 is compulsory, so it cannot be dropped.");
        Browser.Res over = adeola.post("/api/v1/registration/me/change-requests", Map.of("registrationId", registrationId,
                "addCourseIds", List.of(course.get("CSC 211")), "reason", "I want to take one more elective."));
        assertThat(over.json().at("/problems/0/code").asText()).isEqualTo("over_max");

        JsonNode req = ok(adeola.post("/api/v1/registration/me/change-requests", Map.of("registrationId", registrationId,
                "dropCourseIds", List.of(course.get("MTH 211")), "addCourseIds", List.of(course.get("CSC 211")),
                "reason", "MTH 211 clashes with my work shifts; CSC 211 has weekend classes.")), 201);
        changeId = req.get("id").asText();
        assertThat(req.at("/change/summary").asText()).isEqualTo("Drop MTH 211, add CSC 211");
        assertThat(req.at("/change/unitsBefore").asInt()).isEqualTo(18);
        assertThat(req.at("/change/unitsAfter").asInt()).isEqualTo(18);
        assertThat(req.at("/change/maxUnits").asInt()).isEqualTo(20);
        assertThat(adeola.post("/api/v1/registration/me/change-requests", Map.of("registrationId", registrationId,
                "dropCourseIds", List.of(course.get("CSC 209")), "reason", "Another change while one waits.")).json().get("error").asText()).isEqualTo("request_pending");

        assertThat(lawalB.post("/api/v1/registration/requests/" + changeId + "/approve", Map.of()).status()).as("another centre's coordinator").isEqualTo(404);
        JsonNode a03c = ok(adeB.get("/api/v1/registration/requests/" + changeId), 200);
        assertThat(a03c.at("/registration/totalUnits").asInt()).isEqualTo(18);
        assertThat(ok(adeB.get("/api/v1/registration/requests?kind=CHANGE"), 200).get("total").asInt()).isEqualTo(1);
        ok(adeB.post("/api/v1/registration/requests/" + changeId + "/approve", Map.of()), 200);

        // R-09d: approved, the new slip.
        JsonNode me = ok(adeola.get("/api/v1/registration/me"), 200);
        assertThat(me.at("/changeRequest/status").asText()).isEqualTo("APPROVED");
        assertThat(me.at("/changeRequest/decision/by").asText()).isEqualTo("Mrs Bisi Ade");
        JsonNode reg = ok(adeola.get("/api/v1/registration/me/registrations/" + registrationId), 200);
        assertThat(codes(reg.get("courses"))).contains("CSC 211").doesNotContain("MTH 211");
        assertThat(reg.get("totalUnits").asInt()).isEqualTo(18);
        assertThat(reg.get("courseCount").asInt()).isEqualTo(7);
        assertThat(reg.get("slipVersion").asInt()).isEqualTo(2);
        assertThat(reg.get("slipNumber").asText()).as("the number stays").isEqualTo(slipPrefix + "0153");
        assertThat(reg.get("history").findValuesAsText("action")).containsExactly("SUBMITTED", "CHANGE_APPLIED");
        assertThat(reg.at("/history/1/summary").asText()).isEqualTo("Drop MTH 211, add CSC 211 (approved by Mrs Bisi Ade)");
        assertThat(adeola.get("/api/v1/registration/me/registrations/" + registrationId + "/slip.pdf").body()).startsWith("%PDF-");
        assertThat(email(adeolaEmail, "Your course change was approved", 30).get("body").asText()).contains("Drop MTH 211, add CSC 211");
    }

    // ------------------------------------------------------------------ A-07

    @Test
    @Order(6)
    void a07ListsHerWithSevenCoursesAndEighteenUnits() {
        JsonNode report = ok(ngoziB.get("/api/v1/registration/report?size=100"), 200);
        assertThat(report.at("/cards/registered").asInt()).isEqualTo(2);
        assertThat(report.at("/cards/lateRequests").asInt()).isZero();
        assertThat(report.get("total").asInt()).isEqualTo(4);
        JsonNode her = null;
        for (JsonNode r : report.get("items")) if (r.get("matricNumber").asText().equals("WU/ODL/24/0153")) her = r;
        assertThat(her).isNotNull();
        assertThat(her.get("status").asText()).isEqualTo("REGISTERED");
        assertThat(her.get("courseCount").asInt()).isEqualTo(7);
        assertThat(her.get("units").asInt()).isEqualTo(18);
        assertThat(her.get("changedAt").asText()).isNotBlank();
        assertThat(ok(ngoziB.get("/api/v1/registration/report?status=REGISTERED&q=okafor"), 200).get("total").asInt()).isEqualTo(1);
        assertThat(ngoziB.get("/api/v1/registration/report.csv").body()).contains("WU/ODL/24/0153,Adeola Okafor").contains("CSC 211");

        JsonNode a07b = ok(ngoziB.get("/api/v1/registration/students/" + adeolaId), 200);
        assertThat(a07b.at("/registration/slipNumber").asText()).isEqualTo(slipPrefix + "0153");
        assertThat(a07b.get("requests").size()).isEqualTo(1);
        assertThat(a07b.at("/carryOvers/0/code").asText()).isEqualTo("CSC 103");
        assertThat(ngoziB.get("/api/v1/registration/registrations/" + registrationId + "/slip.pdf").body()).startsWith("%PDF-");

        // The Lagos coordinator sees Lagos only; Mrs Ade sees Akure.
        JsonNode lagos = ok(lawalB.get("/api/v1/registration/report"), 200);
        assertThat(lagos.get("items").findValuesAsText("matricNumber")).containsExactly("WU/ODL/24/0219");
        assertThat(lawalB.get("/api/v1/registration/students/" + adeolaId).status()).isEqualTo(404);
        assertThat(lawalB.get("/api/v1/registration/registrations/" + registrationId + "/slip.pdf").status()).isEqualTo(404);
        assertThat(ok(adeB.get("/api/v1/registration/report"), 200).get("total").asInt()).isEqualTo(3);
        assertThat(adeola.get("/api/v1/registration/report").status()).isEqualTo(403);
        assertThat(ngoziB.get("/api/v1/registration/me").status()).as("staff have no registration of their own").isEqualTo(403);

        // A-05 rules: the defaults, a programme's own limits, rules that do not add up.
        JsonNode rules = ok(ngoziB.get("/api/v1/registration/rules"), 200);
        assertThat(rules.at("/fee/blockRegistration").asBoolean()).isTrue();
        assertThat(rules.get("levels").findValuesAsText("maxUnits")).contains("20");
        Browser.Res bad = put(ngoziB, "/api/v1/registration/rules", Map.of("programmeId", programmeId,
                "levels", List.of(Map.of("level", 200, "minUnits", 22, "maxUnits", 20, "maxElectives", 3))));
        assertThat(bad.json().at("/fields/levels[0].minUnits").asText()).isEqualTo("The minimum (22) is above the maximum (20).");
        Browser.Res tight = put(ngoziB, "/api/v1/registration/rules", Map.of("programmeId", programmeId,
                "levels", List.of(Map.of("level", 200, "minUnits", 5, "maxUnits", 9, "maxElectives", 3))));
        assertThat(tight.json().at("/fields/levels[0].maxUnits").asText()).isEqualTo("200L compulsory courses already come to 10 units, over the maximum of 9.");
        JsonNode own = ok(put(ngoziB, "/api/v1/registration/rules", Map.of("programmeId", programmeId,
                "levels", List.of(Map.of("level", 200, "minUnits", 15, "maxUnits", 22, "maxElectives", 3)))), 200);
        assertThat(own.get("inheritedFromDefault").asBoolean()).isFalse();
        assertThat(own.get("programmesWithOwnLimits").size()).isEqualTo(1);
        assertThat(ok(adeola.get("/api/v1/registration/me"), 200).at("/limits/maxUnits").asInt()).isEqualTo(22);
        ok(ngoziB.send("DELETE", "/api/v1/registration/rules/programmes/" + programmeId, null, null), 204);
        assertThat(ok(adeola.get("/api/v1/registration/me"), 200).at("/limits/maxUnits").asInt()).isEqualTo(20);
        assertThat(put(adeB, "/api/v1/registration/rules", Map.of("levels", List.of())).status()).as("coordinators cannot change the rules").isEqualTo(403);
    }

    // ------------------------------------------------------------------ A-06 move to another centre

    @Test
    @Order(7)
    void centreMoveEmailsThreePeopleAndKeepsHistory() throws Exception {
        // An open request that must follow her to Lagos.
        secondChangeId = ok(adeola.post("/api/v1/registration/me/change-requests", Map.of("registrationId", registrationId,
                "dropCourseIds", List.of(course.get("CSC 209")), "addCourseIds", List.of(course.get("MTH 211")),
                "reason", "Linear Algebra fits my new schedule better.")), 201).get("id").asText();
        assertThat(ok(adeB.get("/api/v1/registration/requests"), 200).get("items").findValuesAsText("id")).contains(secondChangeId);

        Browser.Res noReason = ngoziB.send("PATCH", "/api/v1/students/" + adeolaId, Map.of("centreCode", "LAG"), "application/json");
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.json().at("/fields/centreChangeReason").asText()).isEqualTo("Say why the student is moving centre.");
        assertThat(ngoziB.post("/api/v1/students/" + adeolaId + "/move-centre", Map.of("centreCode", "AKR", "reason", "No move")).json().get("error").asText())
                .isEqualTo("same_centre");

        JsonNode moved = ok(ngoziB.post("/api/v1/students/" + adeolaId + "/move-centre",
                Map.of("centreCode", "LAG", "reason", "Moved to Lagos for work", "effectiveOn", today.toString())), 200);
        assertThat(moved.at("/move/status").asText()).isEqualTo("DONE");
        assertThat(moved.at("/move/notified").findValuesAsText("name")).containsExactly("Adeola Okafor", "Mrs Bisi Ade", "Mr Tayo Lawal");
        assertThat(moved.at("/move/notNotified").size()).isZero();
        assertThat(moved.at("/move/summary").asText()).startsWith("Exams and tutorials move to Lagos (Ikeja) from ");
        assertThat(moved.at("/record/centre/code").asText()).isEqualTo("LAG");
        assertThat(moved.at("/record/centreMoves/0/from/name").asText()).isEqualTo("Akure Study Centre");
        assertThat(moved.at("/record/centreMoves/0/reason").asText()).isEqualTo("Moved to Lagos for work");

        assertThat(email(adeolaEmail, "You are moving to Lagos (Ikeja)", 30)).isNotNull();
        assertThat(email(ade, "Adeola Okafor leaves your centre", 30)).isNotNull();
        assertThat(email(lawal, "Adeola Okafor joins your centre", 30)).isNotNull();
        assertThat(ok(ngoziB.get("/api/v1/students/" + adeolaId + "/centre-moves"), 200).get("items").size()).isEqualTo(1);

        // Her open request is now Lagos's to decide.
        assertThat(ok(adeB.get("/api/v1/registration/requests"), 200).get("items").findValuesAsText("id")).doesNotContain(secondChangeId);
        assertThat(adeB.post("/api/v1/registration/requests/" + secondChangeId + "/approve", Map.of()).status()).isEqualTo(404);
        JsonNode lagosQueue = ok(lawalB.get("/api/v1/registration/requests"), 200);
        assertThat(lagosQueue.get("items").findValuesAsText("id")).contains(secondChangeId);
        assertThat(ok(lawalB.post("/api/v1/registration/requests/" + secondChangeId + "/decline",
                Map.of("note", "The MTH 211 weekend class at Lagos is full.")), 200).get("status").asText()).isEqualTo("DECLINED");
        assertThat(ok(ngoziB.get("/api/v1/registration/students/" + adeolaId), 200).at("/registration/centre/name").asText()).isEqualTo("Lagos (Ikeja)");

        // A future move is scheduled, not applied; it can be cancelled.
        JsonNode later = ok(ngoziB.post("/api/v1/students/" + emekaId + "/move-centre",
                Map.of("centreCode", "AKR", "reason", "Moving back home", "effectiveOn", today.plusDays(7).toString())), 200);
        assertThat(later.at("/move/status").asText()).isEqualTo("SCHEDULED");
        assertThat(later.at("/record/centre/code").asText()).isEqualTo("LAG");
        assertThat(ngoziB.post("/api/v1/students/" + emekaId + "/move-centre", Map.of("centreCode", "AKR", "reason", "Again")).json().get("error").asText())
                .isEqualTo("move_scheduled");
        assertThat(ok(ngoziB.post("/api/v1/students/" + emekaId + "/centre-moves/" + later.at("/move/id").asText() + "/cancel", Map.of()), 200)
                .get("status").asText()).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    @Order(8)
    void anotherUniversitySeesNothing() {
        assertThat(yemiB.get("/api/v1/registration/requests/" + changeId).status()).isEqualTo(404);
        assertThat(yemiB.post("/api/v1/registration/requests/" + secondChangeId + "/approve", Map.of()).status()).isEqualTo(404);
        assertThat(yemiB.post("/api/v1/registration/requests/" + bayoLateId + "/decline", Map.of("note", "Not our student at all.")).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/registration/requests/" + bayoLateId + "/attachment").status()).isEqualTo(404);
        assertThat(ok(yemiB.get("/api/v1/registration/requests?status=ALL"), 200).get("total").asInt()).isZero();
        assertThat(yemiB.get("/api/v1/registration/students/" + adeolaId).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/registration/registrations/" + registrationId + "/slip.pdf").status()).isEqualTo(404);
        JsonNode report = ok(yemiB.get("/api/v1/registration/report"), 200);
        assertThat(report.get("total").asInt()).isZero();
        assertThat(report.at("/cards/registered").asInt()).isZero();
        assertThat(yemiB.get("/api/v1/registration/report.csv").body()).doesNotContain("WU/ODL/24/0153");
        assertThat(yemiB.get("/api/v1/registration/carry-overs?studentId=" + adeolaId).status()).isEqualTo(404);
        assertThat(yemiB.post("/api/v1/registration/carry-overs", Map.of("studentId", adeolaId, "courseCode", "CSC 103")).status()).isEqualTo(404);
        assertThat(yemiB.send("DELETE", "/api/v1/registration/carry-overs/" + carryOverId, null, null).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/registration/rules?programmeId=" + programmeId).status()).isEqualTo(404);
        assertThat(put(yemiB, "/api/v1/registration/rules", Map.of("programmeId", programmeId, "levels", List.of())).status()).isEqualTo(404);
        assertThat(put(yemiB, "/api/v1/dev/registration/fee-status", Map.of("studentId", adeolaId, "semesterId", semesterId, "status", "PAID")).status())
                .as("the override is keyed by Crest, so it never reaches Wesley").isEqualTo(204);
        assertThat(ok(adeola.get("/api/v1/registration/me"), 200).at("/fee/status").asText()).isEqualTo("PAID");
        assertThat(new Browser(gw, crestHost).get("/api/v1/public/registration/verify/" + verificationCode).status()).isEqualTo(404);
        assertThat(yemiB.post("/api/v1/students/" + adeolaId + "/move-centre", Map.of("centreCode", "LAG", "reason", "Not ours")).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/students/" + adeolaId + "/centre-moves").status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/registration/me").status()).as("staff, not a student").isEqualTo(403);
    }
}
