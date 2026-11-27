package com.odel.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 academic setup, end to end through the api-gateway (Build plan B1, journey step 1): Mrs Adebayo follows the setup
 * checklist W-10 with the story data (2026/2027, Science → Computer Science → B.Sc. Computer Science, CSC 201, Akure centre
 * with Room 1, Dr Bello and Mrs Ade invited, Dr Bello on CSC 201, default grading) and each step ticks. Then the edge cases
 * (weights 40/50, overlapping bands, a course with no lecturer, duplicate course code, CSV rows with problems saved only on
 * confirm, suspension, two-step reset) and tenant isolation: another university calls every new endpoint with Wesley's ids.
 *
 * Run: ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test -Dtest=AcademicSetupJourneyTest
 * The provider admin must be able to sign in: either two-step not yet set up, or ODEL_E2E_PROVIDER_TOTP_SECRET set.
 */
@EnabledIfEnvironmentVariable(named = "ODEL_E2E_GATEWAY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AcademicSetupJourneyTest {
    final String gw = System.getenv("ODEL_E2E_GATEWAY");
    final String base = Optional.ofNullable(System.getenv("ODEL_E2E_BASE_DOMAIN")).orElse("localhost:3000");
    final String console = Optional.ofNullable(System.getenv("ODEL_E2E_CONSOLE_HOST")).orElse("console." + base);
    final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36);
    final String slug = "acad-" + run, otherSlug = "acadx-" + run;
    final String host = slug + "." + base, otherHost = otherSlug + "." + base;
    final String domain = "acad-" + run + ".edu.ng", otherDomain = "acadx-" + run + ".edu.ng";
    final String ngozi = "ngozi.adebayo@" + domain, bello = "f.bello@" + domain, ade = "b.ade@" + domain;

    Browser chuka, admin, other;
    String chukaSecret, adminSecret, belloSecret;
    final Set<String> usedCodes = new HashSet<>();
    // Wesley's records, used again for the isolation checks.
    String sessionId, facultyId, departmentId, programmeId, courseId, course2Id, centreId, roomId, importId, belloId, adeId;

    // ---- helpers (same approach as PlatformBaseJourneyTest) ----

    static String totp(String base32, long step) {
        try {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            ByteBuffer out = ByteBuffer.allocate(base32.length() * 5 / 8);
            int buffer = 0, bits = 0;
            for (char c : base32.toCharArray()) {
                buffer = (buffer << 5) | alphabet.indexOf(c);
                bits += 5;
                if (bits >= 8) { out.put((byte) (buffer >> (bits - 8))); bits -= 8; }
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(out.array(), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String code(String secret) {
        long now = Instant.now().getEpochSecond() / 30;
        for (long s = now - 1; s <= now + 1; s++) {
            String c = totp(secret, s);
            if (usedCodes.add(secret + c)) return c;
        }
        throw new IllegalStateException("No unused code in the window");
    }

    String firstSignIn(Browser b, String email) {
        assertThat(b.googleSignIn(email)).startsWith("/auth/continue?next=");
        assertThat(b.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_SETUP");
        String secret = b.post("/api/v1/auth/mfa/setup", Map.of()).json().get("secret").asText();
        Browser.Res confirmed = b.post("/api/v1/auth/mfa/setup/confirm", Map.of("code", code(secret)));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        return secret;
    }

    Map<String, Object> university(String name, String shortName, String s, String d, String adminName, String adminEmail) {
        return Map.of("name", name, "shortName", shortName, "slug", s, "googleDomain", d,
                "firstAdmin", Map.of("fullName", adminName, "email", adminEmail),
                "branding", Map.of("primaryColor", "#1A2368", "accentColor", "#C27808"));
    }

    JsonNode checklist(Browser b) {
        Browser.Res r = b.get("/api/v1/setup/checklist");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    JsonNode step(String key) {
        for (JsonNode s : checklist(admin).get("steps")) if (s.get("key").asText().equals(key)) return s;
        throw new IllegalStateException(key);
    }

    JsonNode ok(Browser.Res r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.body() == null || r.body().isBlank() ? null : r.json();
    }

    /** Uploads a CSV straight to storage, the way the browser does. */
    String upload(Browser b, String path, String csv) {
        JsonNode up = ok(b.post(path, Map.of("fileName", "file.csv", "contentType", "text/csv", "size", csv.length())), 200);
        assertThat(b.send("PUT", up.get("uploadUrl").asText(), csv, "text/csv").status()).isEqualTo(200);
        return up.get("key").asText();
    }

    String emailBodyTo(String address) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            for (JsonNode e : chuka.get("/api/v1/dev/emails").json()) {
                if (e.get("to_address").asText().equals(address)) return e.get("subject").asText() + "\n" + e.get("body").asText();
            }
            Thread.sleep(500);
        }
        return null;
    }

    static Map<String, Object> session(String name, boolean active) {
        Map<String, Object> first = Map.of("number", 1, "startsOn", "2026-10-05", "endsOn", "2027-02-26",
                "registration", Map.of("opensOn", "2027-02-02", "closesOn", "2027-02-16"));
        Map<String, Object> second = Map.of("number", 2, "startsOn", "2027-03-15", "endsOn", "2027-07-30");
        return Map.of("name", name, "semesters", List.of(first, second), "makeActive", active);
    }

    static Map<String, Object> rules(int ca, int exam, List<Map<String, Object>> bands) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("caWeight", ca);
        r.put("examWeight", exam);
        r.put("passMark", 40);
        r.put("scale", 5);
        r.put("roundTotals", true);
        r.put("bands", bands);
        r.put("standing", Map.of("probationBelow", 1.00, "withdrawAfterProbationSemesters", 2, "carryOverBeforeGraduating", true,
                "classes", List.of(Map.of("name", "First class", "from", 4.50), Map.of("name", "Second class upper", "from", 3.50),
                        Map.of("name", "Second class lower", "from", 2.40), Map.of("name", "Third class", "from", 1.50), Map.of("name", "Pass", "from", 1.00))));
        return r;
    }

    static List<Map<String, Object>> bands(int cFrom) {
        return List.of(band("A", 70, 100, 5), band("B", 60, 69, 4), band("C", cFrom, 59, 3), band("D", 45, 49, 2), band("E", 40, 44, 1), band("F", 0, 39, 0));
    }

    static Map<String, Object> band(String g, int from, int to, int point) {
        return Map.of("grade", g, "from", from, "to", to, "point", point, "remark", g);
    }

    // ---- the journey ----

    @Test
    @Order(1)
    void providerCreatesTheUniversityAndTheAdminSeesAnEmptyChecklist() {
        chuka = new Browser(gw, console);
        String chukaEmail = Optional.ofNullable(System.getenv("ODEL_E2E_PROVIDER_EMAIL")).orElse("chuka.obi@odel.dev");
        chuka.googleSignIn(chukaEmail);
        if (chuka.get("/api/v1/session").json().get("stage").asText().equals("MFA_SETUP")) {
            chuka = new Browser(gw, console);
            chukaSecret = firstSignIn(chuka, chukaEmail);
        } else {
            chukaSecret = System.getenv("ODEL_E2E_PROVIDER_TOTP_SECRET");
            Assumptions.assumeTrue(chukaSecret != null, "provider already has two-step; set ODEL_E2E_PROVIDER_TOTP_SECRET");
            ok(chuka.post("/api/v1/auth/mfa/verify", Map.of("code", code(chukaSecret))), 200);
        }
        ok(chuka.post("/api/v1/platform/tenants", university("Wesley University", "Wesley ODeL", slug, domain, "Ngozi Adebayo", ngozi)), 201);

        admin = new Browser(gw, host);
        adminSecret = firstSignIn(admin, ngozi);
        JsonNode c = checklist(admin);
        assertThat(c.get("steps")).hasSize(9);
        assertThat(c.get("done").asInt()).isZero();
        assertThat(c.at("/steps/0/status").asText()).isEqualTo("NOT_STARTED");
        assertThat(c.at("/steps/6/actionLabel").asText()).isEqualTo("Review");
        assertThat(c.at("/steps/7/status").asText()).isEqualTo("LOCKED");
        assertThat(c.at("/steps/7/lockedReason").asText()).isEqualTo("Finish steps 1, 2 and 4 first");
        assertThat(c.at("/steps/8/status").asText()).isEqualTo("LOCKED");
    }

    @Test
    @Order(2)
    void step1SessionAndSemesters() {
        // Draft first (W-11c), then made active (W-11b).
        JsonNode draft = ok(admin.post("/api/v1/academic/sessions", Map.of("name", "2026/2027",
                "semesters", List.of(Map.of("number", 1, "startsOn", "2026-10-05", "endsOn", "2027-02-26")), "makeActive", false)), 201);
        sessionId = draft.get("id").asText();
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(step("SESSION").get("status").asText()).isEqualTo("IN_PROGRESS");

        JsonNode s = ok(admin.send("PUT", "/api/v1/academic/sessions/" + sessionId, session("2026/2027", true), "application/json"), 200);
        assertThat(s.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(s.get("registrationLabel").asText()).isEqualTo("2 Feb – 16 Feb 2027");
        assertThat(s.get("semestersLabel").asText()).isEqualTo("1st: Oct–Feb · 2nd: Mar–Jul");

        // Dates in order, no overlap, one name.
        Browser.Res backwards = admin.post("/api/v1/academic/sessions", Map.of("name", "2027/2028",
                "semesters", List.of(Map.of("number", 1, "startsOn", "2027-12-01", "endsOn", "2027-10-01")), "makeActive", false));
        assertThat(ok(backwards, 400).at("/fields/semesters[0].endsOn").asText()).isEqualTo("The first semester must end after it starts.");
        Browser.Res overlap = admin.post("/api/v1/academic/sessions", Map.of("name", "2027/2028",
                "semesters", List.of(Map.of("number", 1, "startsOn", "2027-07-01", "endsOn", "2027-12-01")), "makeActive", false));
        assertThat(ok(overlap, 409).get("message").asText()).startsWith("These dates overlap 2026/2027");
        assertThat(ok(admin.post("/api/v1/academic/sessions", session("2026/2027", false)), 409).get("error").asText()).isEqualTo("name_taken");

        JsonNode st = step("SESSION");
        assertThat(st.get("status").asText()).isEqualTo("DONE");
        assertThat(st.get("summary").asText()).isEqualTo("2026/2027 · 2 semesters · registration 2–16 Feb");
        assertThat(checklist(admin).get("sessionName").asText()).isEqualTo("2026/2027");
    }

    @Test
    @Order(3)
    void step2AcademicStructure() {
        facultyId = ok(admin.post("/api/v1/academic/faculties", Map.of("name", "Faculty of Science", "code", "SCI")), 201).get("id").asText();
        assertThat(step("STRUCTURE").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(ok(admin.post("/api/v1/academic/faculties", Map.of("name", "Faculty of Sciences", "code", "sci")), 409).get("error").asText()).isEqualTo("code_taken");
        departmentId = ok(admin.post("/api/v1/academic/departments", Map.of("facultyId", facultyId, "name", "Department of Computer Science", "code", "CSC")), 201)
                .get("id").asText();
        JsonNode p = ok(admin.post("/api/v1/academic/programmes", Map.of("departmentId", departmentId, "name", "B.Sc. Computer Science", "code", "BSC-CSC",
                "degree", "B.Sc.", "durationYears", 4, "entryLevels", List.of(100))), 201);
        programmeId = p.get("id").asText();
        assertThat(p.get("levels").toString()).isEqualTo("[100,200,300,400]");
        assertThat(p.get("durationLabel").asText()).isEqualTo("4 years · 8 semesters");
        assertThat(p.get("gradingRules").asText()).isEqualTo("Default 5-point scale");
        assertThat(ok(admin.send("PUT", "/api/v1/academic/faculties/" + facultyId, Map.of("name", "Faculty of Science", "code", "SCI"), "application/json"), 200)
                .get("name").asText()).isEqualTo("Faculty of Science");

        JsonNode tree = ok(admin.get("/api/v1/academic/structure"), 200);
        assertThat(tree.at("/faculties/0/departments/0/programmes/0/name").asText()).isEqualTo("B.Sc. Computer Science");
        JsonNode st = step("STRUCTURE");
        assertThat(st.get("status").asText()).isEqualTo("DONE");
        assertThat(st.get("summary").asText()).isEqualTo("1 faculty · 1 department · 1 programme");
    }

    @Test
    @Order(4)
    void step3CourseCatalogue() {
        JsonNode c = ok(admin.post("/api/v1/academic/courses", Map.of("code", "csc201", "title", "Data Structures", "units", 3, "level", 200, "semester", 1,
                "offeredTo", List.of(Map.of("programmeId", programmeId, "kind", "COMPULSORY")))), 201);
        courseId = c.get("id").asText();
        assertThat(c.get("code").asText()).isEqualTo("CSC 201");
        assertThat(c.get("needsLecturer").asBoolean()).isTrue();
        // Duplicate code refused, however it is written.
        Browser.Res dup = admin.post("/api/v1/academic/courses", Map.of("code", "CSC 201", "title", "Something else", "units", 2, "level", 200, "semester", 1, "offeredTo", List.of()));
        assertThat(ok(dup, 409).get("message").asText()).isEqualTo("CSC 201 is already in the catalogue (Data Structures).");
        assertThat(step("CATALOGUE").get("summary").asText()).isEqualTo("1 course");
        assertThat(step("CATALOGUE").get("status").asText()).isEqualTo("DONE");

        // W-13c: a file with bad rows is checked, nothing is saved; confirm saves only the ready rows.
        String csv = "code,title,units,level,semester,programme_code,kind\r\n"
                + "CSC 203,Computer Architecture,3,200,First,BSC-CSC,compulsory\r\n"
                + "CSC 201,Data Structures again,3,200,1,BSC-CSC,compulsory\r\n"
                + "CSC 205,Discrete Mathematics,3,200,1,NOPE,compulsory\r\n"
                + "CSC 207,Operating Systems,twelve,200,1,BSC-CSC,core\r\n";
        String key = upload(admin, "/api/v1/academic/uploads", csv);
        JsonNode check = ok(admin.post("/api/v1/academic/courses/imports", Map.of("key", key, "fileName", "catalogue.csv")), 200);
        importId = check.get("id").asText();
        assertThat(check.get("totalRows").asInt()).isEqualTo(4);
        assertThat(check.get("readyRows").asInt()).isEqualTo(1);
        assertThat(check.get("problemRows").asInt()).isEqualTo(3);
        assertThat(check.get("summary").asText()).isEqualTo("3 rows need fixing");
        assertThat(check.at("/problems/0/row").asInt()).isEqualTo(3);
        assertThat(check.at("/problems/0/message").asText()).contains("CSC 201 is already in the catalogue");
        assertThat(check.at("/problems/1/message").asText()).contains("Programme code NOPE not found");
        assertThat(check.at("/problems/2/message").asText()).contains("Units \"twelve\"");
        assertThat(ok(admin.get("/api/v1/academic/courses"), 200).get("total").asInt()).as("nothing saved before confirm").isEqualTo(1);
        assertThat(admin.get("/api/v1/academic/imports/" + importId + "/problems.csv").body()).contains("problem").contains("Row 4: Programme code NOPE");
        JsonNode done = ok(admin.post("/api/v1/academic/imports/" + importId + "/confirm", Map.of()), 200);
        assertThat(done.get("status").asText()).isEqualTo("IMPORTED");
        assertThat(done.at("/imported/courses").asInt()).isEqualTo(1);
        assertThat(ok(admin.post("/api/v1/academic/imports/" + importId + "/confirm", Map.of()), 409).get("error").asText()).isEqualTo("already_done");
        JsonNode list = ok(admin.get("/api/v1/academic/courses?search=csc%20203"), 200);
        course2Id = list.at("/items/0/id").asText();
        assertThat(list.at("/items/0/offeredTo/0/programmeName").asText()).isEqualTo("B.Sc. Computer Science");
        assertThat(step("CATALOGUE").get("summary").asText()).isEqualTo("2 courses");

        // The structure file works the same way (W-12c).
        String structure = "faculty_name,faculty_code,department_name,department_code,programme_name,programme_code,degree,duration_years,entry_levels\n"
                + "Faculty of Arts,ART,Department of English,ENG,B.A. English,BA-ENG,B.A.,4,100\n"
                + "Faculty of Arts,ART,Department of English,ENG,B.A. Literature,BSC-CSC,B.A.,4,100\n";
        JsonNode sc = ok(admin.post("/api/v1/academic/structure/imports", Map.of("key", upload(admin, "/api/v1/academic/uploads", structure), "fileName", "structure.csv")), 200);
        assertThat(sc.get("problemRows").asInt()).isEqualTo(1);
        assertThat(sc.at("/problems/0/message").asText()).contains("Programme code BSC-CSC already exists");
        assertThat(ok(admin.get("/api/v1/academic/structure"), 200).at("/totals/faculties").asInt()).isEqualTo(1);
        ok(admin.post("/api/v1/academic/imports/" + sc.get("id").asText() + "/cancel", Map.of()), 204);
        assertThat(ok(admin.post("/api/v1/academic/imports/" + sc.get("id").asText() + "/confirm", Map.of()), 409).get("error").asText()).isEqualTo("already_done");
        assertThat(admin.get("/api/v1/academic/imports/templates/catalogue.csv").body()).startsWith("code,title,units,level,semester,programme_code,kind");
    }

    @Test
    @Order(5)
    void step4StudyCentres() {
        JsonNode c = ok(admin.post("/api/v1/academic/centres", Map.of("name", "Akure Study Centre", "code", "AKR", "address", "Oba Adesida Road, Akure",
                "state", "Ondo", "phone", "0803 123 4567")), 201);
        centreId = c.get("id").asText();
        assertThat(step("CENTRES").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(step("CENTRES").get("summary").asText()).isEqualTo("1 centre · 1 without rooms · 1 without a coordinator");
        Browser.Res tooMany = admin.post("/api/v1/academic/centres/" + centreId + "/rooms", Map.of("name", "Room 1", "seats", 30, "computers", 31));
        assertThat(ok(tooMany, 400).at("/fields/computers").asText()).isEqualTo("A room cannot have more computers than seats.");
        roomId = ok(admin.post("/api/v1/academic/centres/" + centreId + "/rooms", Map.of("name", "Room 1", "seats", 30, "computers", 30)), 201).get("id").asText();
        JsonNode centre = ok(admin.get("/api/v1/academic/centres/" + centreId), 200);
        assertThat(centre.get("seats").asInt()).isEqualTo(30);
        assertThat(centre.at("/roomsList/0/name").asText()).isEqualTo("Room 1");
        assertThat(step("CENTRES").get("status").asText()).isEqualTo("DONE");
        // Session, structure and centres done: the import step opens.
        assertThat(step("IMPORT").get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(step("WELCOME").get("status").asText()).isEqualTo("LOCKED");
    }

    @Test
    @Order(6)
    void step5PeopleAndRoles() throws Exception {
        assertThat(step("PEOPLE").get("status").asText()).isEqualTo("NOT_STARTED");
        Browser.Res wrongDomain = admin.post("/api/v1/people", Map.of("fullName", "Dr Funmi Bello", "email", "funmi@gmail.com",
                "roles", List.of(Map.of("role", "LECTURER", "scopeType", "COURSE", "scopeId", courseId))));
        assertThat(ok(wrongDomain, 400).at("/fields/email").asText()).contains("@" + domain);
        JsonNode b = ok(admin.post("/api/v1/people", Map.of("fullName", "Dr Funmi Bello", "email", bello,
                "roles", List.of(Map.of("role", "LECTURER", "scopeType", "COURSE", "scopeId", courseId)))), 201);
        belloId = b.get("id").asText();
        assertThat(b.get("status").asText()).isEqualTo("INVITED");
        assertThat(b.at("/roles/0/scopeLabel").asText()).isEqualTo("CSC 201 · Computer Science");
        JsonNode a = ok(admin.post("/api/v1/people", Map.of("fullName", "Mrs Bisi Ade", "email", ade,
                "roles", List.of(Map.of("role", "CENTRE_COORDINATOR", "scopeType", "CENTRE", "scopeId", centreId)))), 201);
        adeId = a.get("id").asText();
        assertThat(a.at("/roles/0/scopeLabel").asText()).isEqualTo("Akure Study Centre");
        assertThat(ok(admin.post("/api/v1/people", Map.of("fullName", "Dr Funmi Bello", "email", bello,
                "roles", List.of(Map.of("role", "LECTURER", "scopeType", "UNIVERSITY")))), 409).get("error").asText()).isEqualTo("email_taken");
        Browser.Res badScope = admin.post("/api/v1/people", Map.of("fullName", "X Y", "email", "x.y@" + domain,
                "roles", List.of(Map.of("role", "CENTRE_COORDINATOR", "scopeType", "COURSE", "scopeId", courseId))));
        assertThat(ok(badScope, 400).at("/fields/roles[0].scopeType").asText()).startsWith("Centre coordinator can be given for a study centre");

        String email = emailBodyTo(bello);
        assertThat(email).as("invitation email").isNotNull().contains("Lecturer for CSC 201 · Computer Science").contains(host + "/staff/sign-in");

        JsonNode people = ok(admin.get("/api/v1/people"), 200);
        assertThat(people.at("/counts/total").asInt()).isEqualTo(3);
        assertThat(people.at("/counts/invited").asInt()).isEqualTo(2);
        JsonNode st = step("PEOPLE");
        assertThat(st.get("status").asText()).isEqualTo("DONE");
        assertThat(st.get("summary").asText()).isEqualTo("3 staff invited · 1 active");

        // W-15c bulk invite: the bad row is reported and nothing is saved until confirmed.
        String csv = "full_name,email,role,scope_type,scope_code\n"
                + "Mr Kayode Ojo,k.ojo@" + domain + ",Exam officer,DEPARTMENT,CSC\n"
                + "Mr Tobi Lawal,t.lawal@gmail.com,Facilitator,COURSE,CSC 201\n"
                + "Mrs Ada Eze,a.eze@" + domain + ",Lecturer,COURSE,XYZ 999\n";
        JsonNode check = ok(admin.post("/api/v1/people/imports", Map.of("key", upload(admin, "/api/v1/people/uploads", csv), "fileName", "staff.csv")), 200);
        assertThat(check.get("readyRows").asInt()).isEqualTo(1);
        assertThat(check.get("problemRows").asInt()).isEqualTo(2);
        assertThat(check.at("/problems/1/message").asText()).contains("\"XYZ 999\" not found");
        assertThat(ok(admin.get("/api/v1/people"), 200).at("/counts/total").asInt()).isEqualTo(3);
        assertThat(ok(admin.post("/api/v1/people/imports/" + check.get("id").asText() + "/confirm", Map.of()), 200).get("invited").asInt()).isEqualTo(1);
        assertThat(ok(admin.get("/api/v1/people?role=EXAM_OFFICER"), 200).at("/items/0/scopeLabel").asText()).isEqualTo("Department of Computer Science");
    }

    @Test
    @Order(7)
    void step6Staffing() {
        // W-16: courses without a lecturer are counted.
        JsonNode view = ok(admin.get("/api/v1/academic/staffing"), 200);
        assertThat(view.get("needLecturer").asInt()).isEqualTo(2);
        assertThat(view.get("studentsImported").asBoolean()).isFalse();
        assertThat(step("STAFFING").get("summary").asText()).isEqualTo("2 courses still need a lecturer · 1 centre needs a coordinator");
        assertThat(ok(admin.get("/api/v1/academic/courses?lecturer=none"), 200).get("total").asInt()).isEqualTo(2);

        // Only someone with the Lecturer role can lecture.
        Browser.Res wrong = admin.send("PUT", "/api/v1/academic/staffing/courses/" + courseId, Map.of("lecturerId", adeId), "application/json");
        assertThat(ok(wrong, 400).at("/fields/lecturerId").asText()).isEqualTo("Mrs Bisi Ade does not have the Lecturer role. Give it on People & roles first.");

        JsonNode row = ok(admin.send("PUT", "/api/v1/academic/staffing/courses/" + courseId, Map.of("lecturerId", belloId, "notify", true), "application/json"), 200);
        assertThat(row.at("/lecturer/fullName").asText()).isEqualTo("Dr Funmi Bello");
        assertThat(step("STAFFING").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(step("STAFFING").get("summary").asText()).isEqualTo("1 course still needs a lecturer · 1 centre needs a coordinator");
        ok(admin.send("PUT", "/api/v1/academic/staffing/courses/" + course2Id, Map.of("lecturerId", belloId, "notify", false), "application/json"), 200);
        ok(admin.send("PUT", "/api/v1/academic/staffing/centres/" + centreId, Map.of("coordinatorId", adeId), "application/json"), 200);
        assertThat(ok(admin.get("/api/v1/academic/centres/" + centreId), 200).at("/coordinator/fullName").asText()).isEqualTo("Mrs Bisi Ade");
        JsonNode st = step("STAFFING");
        assertThat(st.get("status").asText()).isEqualTo("DONE");
        assertThat(st.get("summary").asText()).isEqualTo("All 2 courses have a lecturer");
        assertThat(step("CENTRES").get("summary").asText()).isEqualTo("1 centre");
        // No earlier session to copy from (W-16d).
        assertThat(ok(admin.post("/api/v1/academic/staffing/copy", Map.of()), 409).get("error").asText()).isEqualTo("no_previous_session");
        assertThat(ok(admin.get("/api/v1/academic/staff-options?role=LECTURER"), 200).at("/items/0/fullName").asText()).isEqualTo("Dr Funmi Bello");
    }

    @Test
    @Order(8)
    void step7GradingRules() {
        JsonNode d = ok(admin.get("/api/v1/academic/grading"), 200);
        assertThat(d.get("saved").asBoolean()).isFalse();
        assertThat(d.get("summary").asText()).isEqualTo("Default 5-point scale (30% CA / 70% exam)");
        assertThat(d.get("example").asText()).isEqualTo("CA 24 + exam 51 = 75 → A (5 points)");
        assertThat(step("GRADING").get("status").asText()).isEqualTo("NOT_STARTED");

        // W-17c: 40/50 refused with a clear message; overlapping bands refused.
        Browser.Res weights = admin.send("PUT", "/api/v1/academic/grading", rules(40, 50, bands(50)), "application/json");
        assertThat(ok(weights, 400).get("message").asText()).isEqualTo("Coursework and exam add up to 90%. They must add up to 100%.");
        Browser.Res overlap = admin.send("PUT", "/api/v1/academic/grading", rules(30, 70, List.of(band("A", 70, 100, 5), band("B", 60, 69, 4),
                band("C", 50, 62, 3), band("D", 45, 49, 2), band("E", 40, 44, 1), band("F", 0, 39, 0))), "application/json");
        assertThat(ok(overlap, 400).at("/fields/bands").asText()).isEqualTo("Bands C (50–62) and B (60–69) overlap at 60 to 62.");
        Browser.Res gap = admin.send("PUT", "/api/v1/academic/grading", rules(30, 70, bands(52)), "application/json");
        assertThat(ok(gap, 400).at("/fields/bands").asText()).isEqualTo("Scores 50 to 51 are in no band.");

        JsonNode saved = ok(admin.send("PUT", "/api/v1/academic/grading", rules(30, 70, bands(50)), "application/json"), 200);
        assertThat(saved.get("saved").asBoolean()).isTrue();
        assertThat(saved.get("version").asInt()).isEqualTo(1);
        assertThat(saved.at("/effectiveSession/name").asText()).isEqualTo("2026/2027");
        // W-17d: a programme's own rules, then back to the default; every save is a version.
        JsonNode own = ok(admin.send("PUT", "/api/v1/academic/grading/programmes/" + programmeId, rules(40, 60, bands(50)), "application/json"), 200);
        assertThat(own.get("summary").asText()).isEqualTo("Own rules: 5-point scale (40% CA / 60% exam)");
        assertThat(ok(admin.get("/api/v1/academic/grading"), 200).get("programmesWithOwnRules").asInt()).isEqualTo(1);
        ok(admin.send("DELETE", "/api/v1/academic/grading/programmes/" + programmeId, null, null), 204);
        JsonNode back = ok(admin.get("/api/v1/academic/grading?programmeId=" + programmeId), 200);
        assertThat(back.get("inheritedFromDefault").asBoolean()).isTrue();
        assertThat(back.get("caWeight").asInt()).isEqualTo(30);
        assertThat(ok(admin.get("/api/v1/academic/grading/history?programmeId=" + programmeId), 200).get("items")).hasSize(2);

        JsonNode c = checklist(admin);
        assertThat(c.get("done").asInt()).isEqualTo(7);
        for (int i = 0; i < 7; i++) assertThat(c.at("/steps/" + i + "/status").asText()).as("step " + (i + 1)).isEqualTo("DONE");
        assertThat(c.at("/steps/7/status").asText()).isEqualTo("NOT_STARTED");
        assertThat(c.at("/steps/8/status").asText()).isEqualTo("LOCKED");
        assertThat(c.get("percent").asInt()).isEqualTo(78);
    }

    @Test
    @Order(9)
    void staffAccountsSuspensionAndTwoStepReset() {
        // Dr Bello signs in: her role reads like the design.
        Browser b = new Browser(gw, host);
        belloSecret = firstSignIn(b, bello);
        JsonNode session = b.get("/api/v1/session").json();
        assertThat(session.at("/roles/0/role").asText()).isEqualTo("LECTURER");
        assertThat(session.at("/roles/0/scopeLabel").asText()).isEqualTo("CSC 201 · Computer Science");
        // A lecturer cannot run setup or see People & roles.
        assertThat(b.get("/api/v1/people").status()).isEqualTo(403);
        assertThat(b.get("/api/v1/setup/checklist").status()).isEqualTo(403);
        assertThat(b.post("/api/v1/academic/faculties", Map.of("name", "X", "code", "X")).status()).isEqualTo(403);
        assertThat(b.get("/api/v1/academic/courses").status()).isEqualTo(200);

        // W-21: the warning names her courses; she is not suspended until confirmed.
        JsonNode warn = ok(admin.get("/api/v1/people/" + belloId + "/suspend-check"), 200);
        assertThat(warn.at("/warnings/0").asText()).contains("teach 2 courses").contains("CSC 201");

        // Reset her two-step: signed out everywhere, the next sign-in sets it up again.
        ok(admin.post("/api/v1/people/" + belloId + "/reset-two-step", Map.of("reason", "Lost phone")), 204);
        assertThat(b.get("/api/v1/session").status()).isEqualTo(401);
        Browser again = new Browser(gw, host);
        again.cookies.putAll(b.cookies);
        assertThat(again.googleSignIn(bello)).startsWith("/auth/continue");
        assertThat(again.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_SETUP");
        assertThat(ok(admin.get("/api/v1/people/" + belloId), 200).at("/person/twoStepSetUp").asBoolean()).isFalse();
        assertThat(ok(admin.post("/api/v1/people/" + ngoziId() + "/reset-two-step", Map.of()), 409).get("error").asText()).isEqualTo("self");

        // Suspended staff cannot sign in; reinstated they can.
        assertThat(ok(admin.post("/api/v1/people/" + adeId + "/suspend", Map.of()), 400).at("/fields/reason").asText()).isNotBlank();
        assertThat(ok(admin.post("/api/v1/people/" + adeId + "/suspend", Map.of("reason", "Left the university on 30 Nov 2026")), 200)
                .get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(new Browser(gw, host).googleSignIn(ade)).endsWith("/staff/sign-in?error=suspended");
        JsonNode staffing = ok(admin.get("/api/v1/academic/staffing"), 200);
        assertThat(staffing.at("/centres/0/coordinator/status").asText()).isEqualTo("SUSPENDED");
        Browser.Res pickSuspended = admin.send("PUT", "/api/v1/academic/staffing/centres/" + centreId, Map.of("coordinatorId", adeId), "application/json");
        assertThat(ok(pickSuspended, 400).at("/fields/coordinatorId").asText()).contains("is suspended");
        assertThat(ok(admin.post("/api/v1/people/" + adeId + "/reinstate", Map.of()), 200).get("status").asText()).isEqualTo("INVITED");
        assertThat(new Browser(gw, host).googleSignIn(ade)).startsWith("/auth/continue");
        ok(admin.post("/api/v1/people/" + adeId + "/resend-invitation", Map.of()), 204);

        // Roles change: Dr Bello also becomes a facilitator.
        JsonNode roles = ok(admin.send("PUT", "/api/v1/people/" + belloId + "/roles", Map.of("roles", List.of(
                Map.of("role", "LECTURER", "scopeType", "COURSE", "scopeId", courseId),
                Map.of("role", "FACILITATOR", "scopeType", "DEPARTMENT", "scopeId", departmentId))), "application/json"), 200);
        assertThat(roles.get("roles")).hasSize(2);
        assertThat(ok(admin.send("PUT", "/api/v1/people/" + ngoziId() + "/roles", Map.of("roles", List.of(
                Map.of("role", "LECTURER", "scopeType", "UNIVERSITY"))), "application/json"), 409).get("error").asText()).isEqualTo("own_admin_role");

        // The university's audit log shows sign-ins, role changes and setup changes, filtered by type.
        List<String> signIns = actions("SIGN_IN"), roleChanges = actions("ROLES"), setup = actions("SETUP");
        assertThat(signIns).contains("SIGN_IN", "MFA_SET_UP", "MFA_RESET");
        assertThat(roleChanges).contains("STAFF_INVITED", "STAFF_SUSPENDED", "STAFF_REINSTATED", "ROLE_GRANTED", "INVITATION_RESENT");
        assertThat(setup).contains("SESSION_CREATED", "FACULTY_CREATED", "COURSE_CREATED", "IMPORT_CATALOGUE", "CENTRE_CREATED", "STAFFING_COURSE_SET", "GRADING_RULES_SAVED");
        assertThat(ok(admin.get("/api/v1/audit?from=2020-01-01&to=2020-12-31"), 200).get("total").asInt()).isZero();
        assertThat(b.get("/api/v1/audit").status()).isEqualTo(401);
    }

    String ngoziId() {
        return admin.get("/api/v1/session").json().at("/user/id").asText();
    }

    List<String> actions(String type) {
        List<String> out = new ArrayList<>();
        JsonNode page = ok(admin.get("/api/v1/audit?size=200&type=" + type), 200);
        page.get("items").forEach(i -> {
            assertThat(i.get("type").asText()).isEqualTo(type);
            out.add(i.get("action").asText());
        });
        return out;
    }

    @Test
    @Order(10)
    void anotherUniversityCannotReadOrChangeAnyOfIt() {
        ok(chuka.post("/api/v1/platform/tenants", university("Crestfield University", "Crestfield ODL", otherSlug, otherDomain, "Yemi Adeoye", "yemi@" + otherDomain)), 201);
        other = new Browser(gw, otherHost);
        firstSignIn(other, "yemi@" + otherDomain);

        // Its own setup is empty, and its lists show nothing of Wesley's.
        assertThat(checklist(other).get("done").asInt()).isZero();
        assertThat(ok(other.get("/api/v1/academic/sessions"), 200).get("items")).isEmpty();
        assertThat(ok(other.get("/api/v1/academic/structure"), 200).get("faculties")).isEmpty();
        assertThat(ok(other.get("/api/v1/academic/courses"), 200).get("total").asInt()).isZero();
        assertThat(ok(other.get("/api/v1/academic/centres"), 200).get("items")).isEmpty();
        assertThat(ok(other.get("/api/v1/people"), 200).get("total").asInt()).isEqualTo(1);
        assertThat(other.get("/api/v1/audit?size=200").body()).doesNotContain("Bello").doesNotContain("Akure");
        assertThat(ok(other.get("/api/v1/academic/grading"), 200).get("saved").asBoolean()).isFalse();
        // The same codes are free in another university.
        ok(other.post("/api/v1/academic/faculties", Map.of("name", "Faculty of Science", "code", "SCI")), 201);

        String json = "application/json";
        Map<String, Object> rules = rules(30, 70, bands(50));
        List<Browser.Res> calls = List.of(
                other.get("/api/v1/academic/sessions/" + sessionId),
                other.send("PUT", "/api/v1/academic/sessions/" + sessionId, session("2030/2031", false), json),
                other.post("/api/v1/academic/sessions/" + sessionId + "/activate", Map.of()),
                other.send("DELETE", "/api/v1/academic/sessions/" + sessionId, null, null),
                other.send("PUT", "/api/v1/academic/faculties/" + facultyId, Map.of("name", "Hijacked", "code", "HJK"), json),
                other.send("PUT", "/api/v1/academic/departments/" + departmentId, Map.of("facultyId", facultyId, "name", "Hijacked", "code", "HJK"), json),
                other.get("/api/v1/academic/programmes/" + programmeId),
                other.send("PUT", "/api/v1/academic/programmes/" + programmeId, Map.of("departmentId", departmentId, "name", "X", "code", "X", "degree", "B.Sc.", "durationYears", 4), json),
                other.get("/api/v1/academic/courses/" + courseId),
                other.send("PUT", "/api/v1/academic/courses/" + courseId, Map.of("code", "CSC 999", "title", "X", "units", 3, "level", 200, "semester", 1), json),
                other.get("/api/v1/academic/centres/" + centreId),
                other.send("PUT", "/api/v1/academic/centres/" + centreId, Map.of("name", "X", "code", "XX"), json),
                other.post("/api/v1/academic/centres/" + centreId + "/rooms", Map.of("name", "Room 9", "seats", 10, "computers", 0)),
                other.send("PUT", "/api/v1/academic/rooms/" + roomId, Map.of("name", "Room 9", "seats", 10, "computers", 0), json),
                other.send("DELETE", "/api/v1/academic/rooms/" + roomId, null, null),
                other.get("/api/v1/academic/staffing?sessionId=" + sessionId),
                other.send("PUT", "/api/v1/academic/staffing/courses/" + courseId, Map.of("sessionId", sessionId), json),
                other.send("PUT", "/api/v1/academic/staffing/centres/" + centreId, Map.of("sessionId", sessionId), json),
                other.get("/api/v1/academic/grading?programmeId=" + programmeId),
                other.send("PUT", "/api/v1/academic/grading/programmes/" + programmeId, rules, json),
                other.send("DELETE", "/api/v1/academic/grading/programmes/" + programmeId, null, null),
                other.get("/api/v1/academic/grading/history?programmeId=" + programmeId),
                other.get("/api/v1/academic/imports/" + importId),
                other.post("/api/v1/academic/imports/" + importId + "/confirm", Map.of()),
                other.post("/api/v1/academic/imports/" + importId + "/cancel", Map.of()),
                other.get("/api/v1/academic/imports/" + importId + "/problems.csv"),
                other.get("/api/v1/people/" + belloId),
                other.send("PUT", "/api/v1/people/" + belloId + "/roles", Map.of("roles", List.of(Map.of("role", "LECTURER", "scopeType", "UNIVERSITY"))), json),
                other.get("/api/v1/people/" + belloId + "/suspend-check"),
                other.post("/api/v1/people/" + belloId + "/suspend", Map.of("reason", "x")),
                other.post("/api/v1/people/" + adeId + "/reinstate", Map.of()),
                other.post("/api/v1/people/" + adeId + "/resend-invitation", Map.of()),
                other.post("/api/v1/people/" + belloId + "/reset-two-step", Map.of()));
        for (Browser.Res r : calls) assertThat(r.status()).as(r.body()).isEqualTo(404);
        // Wesley's ids as references inside a body are refused too.
        assertThat(other.post("/api/v1/academic/departments", Map.of("facultyId", facultyId, "name", "Physics", "code", "PHY")).status()).isEqualTo(400);
        assertThat(other.post("/api/v1/academic/courses", Map.of("code", "PHY 101", "title", "Physics", "units", 3, "level", 100, "semester", 1,
                "offeredTo", List.of(Map.of("programmeId", programmeId, "kind", "COMPULSORY")))).status()).isEqualTo(400);
        assertThat(other.post("/api/v1/people", Map.of("fullName", "A B", "email", "a.b@" + otherDomain,
                "roles", List.of(Map.of("role", "LECTURER", "scopeType", "COURSE", "scopeId", courseId)))).status()).isEqualTo(400);
        assertThat(other.post("/api/v1/academic/courses/imports", Map.of("key", "imports/" + UUID.randomUUID() + "/x.csv")).status()).isEqualTo(400);
        // Wesley is unchanged.
        assertThat(ok(admin.get("/api/v1/academic/centres/" + centreId), 200).get("rooms").asInt()).isEqualTo(1);
        assertThat(ok(admin.get("/api/v1/people/" + belloId), 200).at("/person/status").asText()).isEqualTo("ACTIVE");
        assertThat(checklist(admin).get("done").asInt()).isEqualTo(7);
    }
}
