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
 * B3 teach and learn end-to-end (Build plan B3), through the api-gateway of a running stack with the dev profile and the fake video
 * host (ODEL_VIDEO_MODE=fake). A fresh university: the ODeL admin sets up the session, B.Sc. Computer Science 200L courses, Akure and
 * Lagos centres, Dr Funmi Bello on CSC 201 and Dr Musa Lawal on CSC 203, Mrs Bisi Ade coordinating Akure; Adeola (Akure) and Emeka
 * (Lagos) register, Chidi does not.
 *
 * Journey: Dr Bello fills CSC 201 week 5 with a video, notes (PDF), slides, a link and a page and posts an announcement; the video goes
 * uploading → preparing (E-10) → ready by Bunny's (fake) webhook, checked against the host; a second video fails (T-04b) and is uploaded
 * again; Adeola watches at 360p (qualities with sizes, audio only, resume), reads the notes and slides, finishes the week; Dr Bello sees
 * her progress (T-07, T-07b) and Emeka not started; the coordinator sees only Akure. Edge cases: not registered, week not open (E-16), empty
 * course (E-11), read-only roles, another lecturer, another university, hide/move/reorder/replace/delete, the same webhook twice, a webhook
 * for another library, copying last session's course (T-06) and dating its weeks.
 *
 * Run: ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test -Dtest=ContentJourneyTest
 */
@EnabledIfEnvironmentVariable(named = "ODEL_E2E_GATEWAY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentJourneyTest {
    final String gw = System.getenv("ODEL_E2E_GATEWAY");
    final String base = Optional.ofNullable(System.getenv("ODEL_E2E_BASE_DOMAIN")).orElse("localhost:3000");
    final String console = Optional.ofNullable(System.getenv("ODEL_E2E_CONSOLE_HOST")).orElse("console." + base);
    final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36) + Integer.toString(new Random().nextInt(1296), 36);
    final String wesleySlug = "wes-con-" + run, crestSlug = "crest-con-" + run;
    final String wesleyHost = wesleySlug + "." + base, crestHost = crestSlug + "." + base;
    final String wesleyDomain = "wes-con-" + run + ".edu.ng", crestDomain = "crest-con-" + run + ".edu.ng";
    final String ngozi = "ngozi.adebayo@" + wesleyDomain, bello = "funmi.bello@" + wesleyDomain, lawal = "musa.lawal@" + wesleyDomain,
            ade = "bisi.ade@" + wesleyDomain, yemi = "yemi.adeoye@" + crestDomain;
    final String studentMail = "students-con-" + run + ".test";
    final String adeolaEmail = "adeola.okafor@" + studentMail, chidiEmail = "chidi.nwosu@" + studentMail, emekaEmail = "emeka.obi@" + studentMail;
    final Set<String> usedCodes = new HashSet<>();
    final LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
    final int firstYear = today.getMonthValue() >= 8 ? today.getYear() : today.getYear() - 1;
    final String sessionName = firstYear + "/" + (firstYear + 1), lastSessionName = (firstYear - 1) + "/" + firstYear;

    Browser chuka, ngoziB, belloB, lawalB, adeB, yemiB, adeola, chidi, emeka;
    String sessionId, semesterId, programmeId, akureId, lagosId, belloId, lawalId, adeId;
    String adeolaId, chidiId, emekaId;
    final Map<String, String> course = new HashMap<>();
    String csc201, csc203, week4, week5, week6, linkId, pageId, notesId, slidesId, videoId, videoHostId, video2Id, announcementId;

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

    void createUniversity(String name, String slug, String domain, String admin, String adminEmail) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", name + " (" + slug + ")");
        u.put("shortName", name.split(" ")[0] + " ODeL");
        u.put("slug", slug);
        u.put("googleDomain", domain);
        u.put("firstAdmin", Map.of("fullName", admin, "email", adminEmail));
        u.put("branding", Map.of("primaryColor", "#1A2368", "accentColor", "#C27808"));
        Browser.Res created = chuka.post("/api/v1/platform/tenants", u);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
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

    static String error(Browser.Res r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.json().get("error").asText();
    }

    static Browser.Res put(Browser b, String path, Object body) {
        return b.send("PUT", path, body, "application/json");
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

    void register(Browser b, String studentId) {
        ok(put(ngoziB, "/api/v1/dev/registration/fee-status", Map.of("studentId", studentId, "semesterId", semesterId, "status", "PAID")), 204);
        ok(b.post("/api/v1/registration/me/submit", Map.of("electiveIds", List.of(course.get("CSC 209"), course.get("MTH 211")), "confirm", true)), 201);
    }

    JsonNode item(JsonNode week, String id) {
        for (JsonNode i : week.get("items")) if (i.get("id").asText().equals(id)) return i;
        return null;
    }

    /** Uploads a file the way the browser does: ask for a link, PUT the bytes to storage, say it is done. */
    JsonNode uploadFile(Browser b, String weekId, String fileName, String contentType, byte[] bytes) {
        JsonNode started = ok(b.post("/api/v1/content/weeks/" + weekId + "/uploads",
                Map.of("fileName", fileName, "contentType", contentType, "sizeBytes", bytes.length)), 201);
        assertThat(started.at("/upload/protocol").asText()).isEqualTo("PUT");
        String url = started.at("/upload/url").asText();
        assertThat(url).startsWith("/api/v1/dev/storage/content/");
        assertThat(b.send("PUT", url, new String(bytes, StandardCharsets.ISO_8859_1), contentType).status()).isEqualTo(200);
        return started;
    }

    static final String PDF = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
            + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n";

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
        createUniversity("Wesley University", wesleySlug, wesleyDomain, "Ngozi Adebayo", ngozi);
        createUniversity("Crestfield University", crestSlug, crestDomain, "Yemi Adeoye", yemi);
        ngoziB = new Browser(gw, wesleyHost);
        firstSignIn(ngoziB, ngozi);
        yemiB = new Browser(gw, crestHost);
        firstSignIn(yemiB, yemi);
        assertThat(ngoziB.get("/api/v1/content/me/courses").status()).as("content-service must be running (staff get 403 here)").isEqualTo(403);

        String faculty = created(ngoziB.post("/api/v1/academic/faculties", Map.of("name", "Faculty of Science", "code", "SCI")));
        String dept = created(ngoziB.post("/api/v1/academic/departments", Map.of("facultyId", faculty, "name", "Computer Science", "code", "CSC")));
        programmeId = created(ngoziB.post("/api/v1/academic/programmes", Map.of("departmentId", dept, "name", "B.Sc. Computer Science", "code", "BSC-CSC-ODL",
                "degree", "B.Sc.", "durationYears", 4, "entryLevels", List.of(100, 200))));
        akureId = created(ngoziB.post("/api/v1/academic/centres", Map.of("name", "Akure Study Centre", "code", "AKR", "address", "1 Oba Adesida Road", "state", "Ondo", "phone", "0803 555 0117")));
        lagosId = created(ngoziB.post("/api/v1/academic/centres", Map.of("name", "Lagos (Ikeja)", "code", "LAG", "address", "2 Allen Avenue", "state", "Lagos", "phone", "0803 555 0142")));
        Object[][] catalogue = {
                {"CSC 201", "Data Structures", 3, "COMPULSORY"}, {"CSC 203", "Computer Architecture", 3, "COMPULSORY"},
                {"CSC 205", "Discrete Mathematics", 2, "COMPULSORY"}, {"GST 201", "Entrepreneurship Studies", 2, "COMPULSORY"},
                {"CSC 209", "Web Technologies", 2, "ELECTIVE"}, {"MTH 211", "Linear Algebra I", 3, "ELECTIVE"}};
        for (Object[] c : catalogue) {
            course.put((String) c[0], created(ngoziB.post("/api/v1/academic/courses", Map.of("code", c[0], "title", c[1], "units", c[2], "level", 200, "semester", 1,
                    "offeredTo", List.of(Map.of("programmeId", programmeId, "kind", c[3]))))));
        }
        // Last session (for the copy) and this one, open for registration.
        LocalDate ls1 = today.minusDays(430), le1 = today.minusDays(330), ls2 = le1.plusDays(10), le2 = ls2.plusDays(100);
        ok(ngoziB.post("/api/v1/academic/sessions", Map.of("name", lastSessionName, "makeActive", false, "semesters", List.of(
                Map.of("number", 1, "startsOn", ls1.toString(), "endsOn", le1.toString()),
                Map.of("number", 2, "startsOn", ls2.toString(), "endsOn", le2.toString())))), 201);
        LocalDate s1 = today.minusDays(60), e1 = today.plusDays(60), s2 = e1.plusDays(10), e2 = s2.plusDays(120);
        JsonNode s = ok(ngoziB.post("/api/v1/academic/sessions", Map.of("name", sessionName, "makeActive", true, "semesters", List.of(
                Map.of("number", 1, "startsOn", s1.toString(), "endsOn", e1.toString(), "registration",
                        Map.of("opensOn", today.minusDays(1).toString(), "closesOn", today.plusDays(5).toString(), "lateClosesOn", today.plusDays(10).toString())),
                Map.of("number", 2, "startsOn", s2.toString(), "endsOn", e2.toString())))), 201);
        sessionId = s.get("id").asText();
        semesterId = s.at("/semesters/0/id").asText();

        belloId = created(ngoziB.post("/api/v1/people", Map.of("fullName", "Dr Funmi Bello", "email", bello,
                "roles", List.of(Map.of("role", "LECTURER", "scopeType", "COURSE", "scopeId", course.get("CSC 201"))))));
        lawalId = created(ngoziB.post("/api/v1/people", Map.of("fullName", "Dr Musa Lawal", "email", lawal,
                "roles", List.of(Map.of("role", "LECTURER", "scopeType", "COURSE", "scopeId", course.get("CSC 203"))))));
        adeId = created(ngoziB.post("/api/v1/people", Map.of("fullName", "Mrs Bisi Ade", "email", ade,
                "roles", List.of(Map.of("role", "CENTRE_COORDINATOR", "scopeType", "CENTRE", "scopeId", akureId)))));
        ok(put(ngoziB, "/api/v1/academic/staffing/courses/" + course.get("CSC 201"), Map.of("lecturerId", belloId, "notify", false)), 200);
        ok(put(ngoziB, "/api/v1/academic/staffing/courses/" + course.get("CSC 203"), Map.of("lecturerId", lawalId, "notify", false)), 200);
        ok(put(ngoziB, "/api/v1/academic/staffing/centres/" + akureId, Map.of("coordinatorId", adeId, "notify", false)), 200);
        belloB = new Browser(gw, wesleyHost);
        firstSignIn(belloB, bello);
        lawalB = new Browser(gw, wesleyHost);
        firstSignIn(lawalB, lawal);
        adeB = new Browser(gw, wesleyHost);
        firstSignIn(adeB, ade);

        adeolaId = addStudent("Okafor", "Adeola", "WU/ODL/24/0153", "AKR", adeolaEmail);
        chidiId = addStudent("Nwosu", "Chidi", "WU/ODL/24/0154", "AKR", chidiEmail);
        emekaId = addStudent("Obi", "Emeka", "WU/ODL/24/0219", "LAG", emekaEmail);
        adeola = activate("WU/ODL/24/0153", adeolaEmail);
        chidi = activate("WU/ODL/24/0154", chidiEmail);
        emeka = activate("WU/ODL/24/0219", emekaEmail);
        register(adeola, adeolaId);
        register(emeka, emekaId);
    }

    // ------------------------------------------------------------------ T-01, T-02b, T-08

    @Test
    @Order(1)
    void drBelloSeesHerCourseEmpty() {
        JsonNode list = ok(belloB.get("/api/v1/content/courses"), 200);
        assertThat(list.at("/semester/label").asText()).isEqualTo(sessionName + " · 1st semester");
        assertThat(list.get("items").size()).as("only the courses she teaches").isEqualTo(1);
        JsonNode card = list.at("/items/0");
        csc201 = card.get("offeringId").asText();
        assertThat(card.at("/course/code").asText()).isEqualTo("CSC 201");
        assertThat(card.at("/lecturer/initials").asText()).isEqualTo("FB");
        assertThat(card.get("canEdit").asBoolean()).isTrue();
        assertThat(card.get("students").asInt()).isEqualTo(2);
        assertThat(card.get("readyLabel").asText()).isEqualTo("0 of 0 weeks ready");
        assertThat(card.get("empty").asBoolean()).isTrue();
        assertThat(list.get("subtitle").asText()).isEqualTo("1 course · 2 students");

        // The admin sees every first-semester course.
        JsonNode all = ok(ngoziB.get("/api/v1/content/courses"), 200);
        assertThat(all.get("items").size()).isEqualTo(6);
        for (JsonNode c : all.get("items")) if (c.at("/course/code").asText().equals("CSC 203")) csc203 = c.get("offeringId").asText();

        JsonNode over = ok(belloB.get("/api/v1/content/offerings/" + csc201), 200);
        assertThat(over.get("empty").asBoolean()).isTrue();
        assertThat(over.get("canCopy").asBoolean()).isFalse();
        assertThat(over.get("assessment").asText()).contains("set by the ODeL office");

        JsonNode saved = ok(put(belloB, "/api/v1/content/offerings/" + csc201, Map.of("outline", "Lists, stacks, queues, trees and hashing, with the cost of each.",
                "objectives", List.of("Choose the right structure for a problem", "Implement linked lists", "Analyse running time"))), 200);
        assertThat(saved.get("objectives").size()).isEqualTo(3);
        assertThat(error(put(belloB, "/api/v1/content/offerings/" + csc201, Map.of("outline", "x".repeat(601))), 400)).isEqualTo("invalid");
    }

    // ------------------------------------------------------------------ T-03: weeks, links, pages

    @Test
    @Order(2)
    void weeksLinksAndPages() {
        week4 = ok(belloB.post("/api/v1/content/offerings/" + csc201 + "/weeks", Map.of("title", "Linked lists I", "opensOn", today.minusDays(8).toString())), 201)
                .get("id").asText();
        JsonNode w5 = ok(belloB.post("/api/v1/content/offerings/" + csc201 + "/weeks", Map.of("title", "Linked lists II", "opensOn", today.minusDays(1).toString(),
                "note", "Watch lecture 5 before Thursday's live class. The quiz opens on Friday.")), 201);
        week5 = w5.get("id").asText();
        assertThat(w5.get("number").asInt()).isEqualTo(2);
        assertThat(w5.get("releaseLabel").asText()).startsWith("Released ");
        JsonNode w6 = ok(belloB.post("/api/v1/content/offerings/" + csc201 + "/weeks", Map.of("title", "Stacks and queues", "opensOn", today.plusDays(6).toString())), 201);
        week6 = w6.get("id").asText();
        assertThat(w6.get("message").asText()).startsWith("Week 3 added · opens ");
        assertThat(error(belloB.post("/api/v1/content/offerings/" + csc201 + "/weeks", Map.of("title", " ")), 400)).isEqualTo("invalid");

        ok(belloB.post("/api/v1/content/weeks/" + week4 + "/items", Map.of("kind", "PAGE", "title", "Welcome to week 4", "body", "Read chapter 3.")), 201);
        JsonNode link = ok(belloB.post("/api/v1/content/weeks/" + week5 + "/items", Map.of("kind", "LINK", "title", "Visualgo: linked lists",
                "url", "https://visualgo.net/en/list", "note", "Step through insert and delete.", "warnData", true)), 201);
        linkId = link.get("id").asText();
        assertThat(link.get("meta").asText()).isEqualTo("Link · visualgo.net · students are warned it uses data");
        assertThat(link.get("statusLabel").asText()).isEqualTo("Ready");
        JsonNode page = ok(belloB.post("/api/v1/content/weeks/" + week5 + "/items", Map.of("kind", "PAGE", "title", "How to prepare for Thursday's Q&A",
                "body", "Bring two questions about pointers.")), 201);
        pageId = page.get("id").asText();
        assertThat(page.get("statusLabel").asText()).isEqualTo("Published");
        assertThat(page.get("meta").asText()).isEqualTo("Text page · 1 min read");
        Browser.Res badLink = belloB.post("/api/v1/content/weeks/" + week5 + "/items", Map.of("kind", "LINK", "title", "Bad", "url", "visualgo"));
        assertThat(badLink.json().at("/fields/url").asText()).isEqualTo("Paste the full web address, starting with https://");

        JsonNode weeks = ok(belloB.get("/api/v1/content/offerings/" + csc201 + "/weeks"), 200);
        assertThat(weeks.at("/items/1/statusLabel").asText()).isEqualTo("2 items · released");
        assertThat(weeks.at("/items/2/statusLabel").asText()).startsWith("Empty · opens ");
    }

    // ------------------------------------------------------------------ T-04: documents

    @Test
    @Order(3)
    void notesAndSlides() {
        JsonNode notes = uploadFile(belloB, week5, "week-05-notes.pdf", "application/pdf", PDF.getBytes(StandardCharsets.ISO_8859_1));
        notesId = notes.at("/item/id").asText();
        assertThat(notes.at("/item/status").asText()).isEqualTo("UPLOADING");
        assertThat(notes.at("/item/title").asText()).isEqualTo("Week 05 notes");
        JsonNode done = ok(belloB.post("/api/v1/content/items/" + notesId + "/uploaded", Map.of()), 200);
        assertThat(done.get("status").asText()).isEqualTo("READY");
        ok(put(belloB, "/api/v1/content/items/" + notesId, Map.of("title", "Week 5 notes")), 200);

        JsonNode slides = uploadFile(belloB, week5, "week-05-slides.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "not really a presentation".getBytes(StandardCharsets.UTF_8));
        slidesId = slides.at("/item/id").asText();
        assertThat(ok(belloB.post("/api/v1/content/items/" + slidesId + "/uploaded", Map.of()), 200).get("status").asText()).isEqualTo("CONVERTING");
        ok(ngoziB.post("/api/v1/dev/content/jobs/run", Map.of()), 200);
        JsonNode w = ok(belloB.get("/api/v1/content/weeks/" + week5), 200);
        // No LibreOffice on a laptop (or a file it cannot read): kept as the original; on the dev box a real deck converts.
        assertThat(item(w, slidesId).get("status").asText()).isIn("KEPT_ORIGINAL", "READY");
        assertThat(item(w, notesId).get("pages").asInt()).as("page count from the PDF").isEqualTo(1);
        assertThat(item(w, notesId).get("meta").asText()).startsWith("PDF · ").endsWith(" · 1 page");

        // Refusals say what to do.
        assertThat(belloB.post("/api/v1/content/weeks/" + week5 + "/uploads", Map.of("fileName", "code.zip", "sizeBytes", 10)).json().get("message").asText())
                .isEqualTo("We cannot use .zip files. Upload a video, audio, PDF, PowerPoint or Word file.");
        assertThat(error(belloB.post("/api/v1/content/weeks/" + week5 + "/uploads", Map.of("fileName", "huge.mp4", "sizeBytes", 3L * 1024 * 1024 * 1024)), 400))
                .isEqualTo("file_too_large");
        JsonNode missing = ok(belloB.post("/api/v1/content/weeks/" + week5 + "/uploads", Map.of("fileName", "never.pdf", "sizeBytes", 100)), 201);
        assertThat(error(belloB.post("/api/v1/content/items/" + missing.at("/item/id").asText() + "/uploaded", Map.of()), 409)).isEqualTo("upload_missing");
        ok(belloB.delete("/api/v1/content/items/" + missing.at("/item/id").asText(), null), 204);
    }

    // ------------------------------------------------------------------ T-04, E-10, T-04b: videos

    @Test
    @Order(4)
    void videoUploadsStraightToTheHostAndBecomesReady() throws Exception {
        JsonNode started = ok(belloB.post("/api/v1/content/weeks/" + week5 + "/uploads",
                Map.of("fileName", "lecture-05.mp4", "contentType", "video/mp4", "sizeBytes", 214_000_000, "title", "Lecture 5: Linked lists")), 201);
        videoId = started.at("/item/id").asText();
        String uploadUrl = started.at("/upload/url").asText();
        assertThat(uploadUrl).startsWith("/api/v1/dev/content/videos/");
        videoHostId = uploadUrl.split("/")[6];
        assertThat(started.at("/item/meta").asText()).isEqualTo("Video · waiting for the upload to finish");
        assertThat(belloB.send("PUT", uploadUrl, "fake video bytes", "video/mp4").status()).isEqualTo(204);
        JsonNode processing = ok(belloB.post("/api/v1/content/items/" + videoId + "/uploaded", Map.of()), 200);
        assertThat(processing.get("status").asText()).isEqualTo("PROCESSING");
        assertThat(processing.get("statusLabel").asText()).isEqualTo("Processing");
        assertThat(processing.get("meta").asText()).contains("making 240p/360p/480p + audio");

        // E-10: Adeola sees it is being prepared, and gets no playback.
        JsonNode detail = ok(adeola.get("/api/v1/content/me/items/" + videoId), 200);
        assertThat(detail.at("/item/ready").asBoolean()).isFalse();
        assertThat(detail.at("/item/statusLabel").asText()).isEqualTo("Not ready");
        assertThat(detail.at("/notReady/code").asText()).isEqualTo("video_preparing");
        assertThat(detail.at("/notReady/message").asText()).startsWith("Being prepared. Dr Funmi Bello uploaded it ");
        assertThat(error(adeola.get("/api/v1/content/me/items/" + videoId + "/playback"), 409)).isEqualTo("video_preparing");

        // Bunny's webhook from another library is refused; ours moves it on only after asking the host.
        Browser anyone = new Browser(gw, wesleyHost);
        assertThat(error(anyone.post("/api/v1/content/webhooks/bunny", Map.of("VideoLibraryId", "999", "VideoGuid", videoHostId, "Status", 3)), 401)).isEqualTo("bad_library");
        JsonNode lie = ok(anyone.post("/api/v1/content/webhooks/bunny", Map.of("VideoLibraryId", "fake-library", "VideoGuid", videoHostId, "Status", 3)), 200);
        assertThat(lie.get("changed").asInt()).as("the host says it is still encoding: a webhook saying 'finished' is not believed").isZero();
        JsonNode sim = ok(ngoziB.post("/api/v1/dev/content/videos/" + videoHostId + "/simulate", Map.of("outcome", "READY", "lengthSeconds", 1084)), 200);
        JsonNode again = ok(anyone.post("/api/v1/content/webhooks/bunny", sim.get("webhook")), 200);
        assertThat(again.get("changed").asInt()).as("the same webhook twice changes nothing").isZero();

        JsonNode w = ok(belloB.get("/api/v1/content/weeks/" + week5), 200);
        JsonNode v = item(w, videoId);
        assertThat(v.get("status").asText()).isEqualTo("READY");
        assertThat(v.get("durationSeconds").asInt()).isEqualTo(1084);
        assertThat(v.get("meta").asText()).isEqualTo("Video · 18 min · 240p/360p/480p + audio being made");
        ok(ngoziB.post("/api/v1/dev/content/jobs/run", Map.of()), 200);
        assertThat(item(ok(belloB.get("/api/v1/content/weeks/" + week5), 200), videoId).get("meta").asText()).endsWith("+ audio ready");
        boolean bell = false;
        for (JsonNode n : ok(belloB.get("/api/v1/notifications"), 200).get("items")) bell |= n.get("title").asText().equals("Lecture 5: Linked lists is ready");
        assertThat(bell).as("Dr Bello's bell says the video is ready").isTrue();

        // A second video fails to encode (T-04b), then is uploaded again.
        JsonNode second = ok(belloB.post("/api/v1/content/weeks/" + week5 + "/uploads",
                Map.of("fileName", "lecture-05b.mp4", "sizeBytes", 90_000_000, "title", "Lecture 5b: Doubly linked lists")), 201);
        video2Id = second.at("/item/id").asText();
        String host2 = second.at("/upload/url").asText().split("/")[6];
        belloB.send("PUT", second.at("/upload/url").asText(), "x", "video/mp4");
        ok(belloB.post("/api/v1/content/items/" + video2Id + "/uploaded", Map.of()), 200);
        ok(ngoziB.post("/api/v1/dev/content/videos/" + host2 + "/simulate", Map.of("outcome", "FAILED")), 200);
        JsonNode failed = item(ok(belloB.get("/api/v1/content/weeks/" + week5), 200), video2Id);
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.at("/failure/message").asText()).startsWith("Bunny could not prepare this video. Upload it again");
        assertThat(email(bello, "Lecture 5b: Doubly linked lists could not be prepared", 30)).isNotNull();
        assertThat(ok(adeola.get("/api/v1/content/me/items/" + video2Id), 200).at("/notReady/code").asText()).isEqualTo("video_failed");
        JsonNode retry = ok(belloB.post("/api/v1/content/items/" + video2Id + "/upload-again", Map.of()), 200);
        assertThat(retry.at("/upload/url").asText()).as("a new video at the host").doesNotContain(host2);
        assertThat(retry.at("/item/status").asText()).isEqualTo("UPLOADING");
    }

    // ------------------------------------------------------------------ student: L-03, L-04, L-05, E-11, E-16, not registered

    @Test
    @Order(5)
    void onlyRegisteredStudentsGetIn() {
        JsonNode none = ok(chidi.get("/api/v1/content/me/courses"), 200);
        assertThat(none.get("registered").asBoolean()).isFalse();
        assertThat(none.get("message").asText()).startsWith("You have not registered your courses for " + sessionName + " · 1st semester yet.");
        assertThat(none.get("registerPath").asText()).isEqualTo("/registration");
        assertThat(error(chidi.get("/api/v1/content/me/offerings/" + csc201), 403)).isEqualTo("not_registered");
        assertThat(error(chidi.get("/api/v1/content/me/items/" + videoId + "/playback"), 403)).isEqualTo("not_registered");
        assertThat(error(chidi.get("/api/v1/content/me/weeks/" + week5), 403)).isEqualTo("not_registered");
        assertThat(error(adeola.get("/api/v1/content/courses"), 403)).isEqualTo("forbidden");
    }

    @Test
    @Order(6)
    void adeolaSeesHerCoursesAndWeeks() {
        JsonNode list = ok(adeola.get("/api/v1/content/me/courses"), 200);
        assertThat(list.get("registered").asBoolean()).isTrue();
        assertThat(list.get("subtitle").asText()).isEqualTo("6 registered courses · 15 units");
        JsonNode c201 = null;
        for (JsonNode c : list.get("items")) if (c.at("/course/code").asText().equals("CSC 201")) c201 = c;
        assertThat(c201).isNotNull();
        assertThat(c201.get("offeringId").asText()).isEqualTo(csc201);
        assertThat(c201.at("/lecturer/fullName").asText()).isEqualTo("Dr Funmi Bello");
        assertThat(c201.at("/currentWeek/number").asInt()).isEqualTo(2);
        assertThat(c201.at("/progress/label").asText()).isEqualTo("0 of 3 weeks done · 0%");

        JsonNode page = ok(adeola.get("/api/v1/content/me/offerings/" + csc201), 200);
        assertThat(page.at("/about/objectives").size()).isEqualTo(3);
        assertThat(page.get("weeks").size()).isEqualTo(3);
        assertThat(page.at("/weeks/2/open").asBoolean()).isFalse();
        assertThat(page.at("/weeks/2/openLabel").asText()).isEqualTo("Opens " + day(today.plusDays(6)));
        assertThat(page.at("/weeks/1/current").asBoolean()).isTrue();

        // E-16: a week not open yet.
        Browser.Res closed = adeola.get("/api/v1/content/me/weeks/" + week6);
        assertThat(error(closed, 409)).isEqualTo("week_not_open");
        assertThat(closed.json().get("message").asText()).isEqualTo("Week 3 opens " + day(today.plusDays(6)) + ". Come back then.");
        assertThat(closed.json().get("openLabel").asText()).startsWith("Opens ");

        // E-11: CSC 203 has nothing yet.
        String c203 = null;
        for (JsonNode c : list.get("items")) if (c.at("/course/code").asText().equals("CSC 203")) c203 = c.get("offeringId").asText();
        JsonNode empty = ok(adeola.get("/api/v1/content/me/offerings/" + c203), 200);
        assertThat(empty.get("empty").asBoolean()).isTrue();
        assertThat(empty.get("emptyMessage").asText()).startsWith("Dr Musa Lawal has not added anything to this course yet.");

        JsonNode w5 = ok(adeola.get("/api/v1/content/me/weeks/" + week5), 200);
        assertThat(w5.get("note").asText()).startsWith("Watch lecture 5");
        List<String> titles = new ArrayList<>();
        w5.get("items").forEach(i -> titles.add(i.get("title").asText()));
        assertThat(titles).containsExactly("Visualgo: linked lists", "How to prepare for Thursday's Q&A", "Week 5 notes", "Week 05 slides",
                "Lecture 5: Linked lists", "Lecture 5b: Doubly linked lists");
        assertThat(item(w5, linkId).get("dataCost").asText()).isEqualTo("opens your browser, uses data");
        assertThat(item(w5, pageId).get("dataCost").asText()).isEqualTo("under 10 KB");
        assertThat(item(w5, videoId).get("meta").asText()).isEqualTo("Video · 18 min");
        assertThat(item(w5, videoId).get("dataCost").asText()).matches("about \\d+ MB at 360p · \\d+(\\.\\d)? MB audio only");
        assertThat(item(w5, video2Id).get("ready").asBoolean()).isFalse();
        assertThat(w5.get("progressLabel").asText()).as("the failed video does not count").isEqualTo("0 of 5 done");
    }

    static String day(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK));
    }

    // ------------------------------------------------------------------ L-06, L-07, L-08: playing, resume, done

    @Test
    @Order(7)
    void adeolaWatchesAt360pListensAndFinishesTheWeek() {
        JsonNode play = ok(adeola.get("/api/v1/content/me/items/" + videoId + "/playback"), 200);
        assertThat(play.get("defaultQuality").asText()).isEqualTo("360p");
        assertThat(play.get("durationSeconds").asInt()).isEqualTo(1084);
        List<String> qs = new ArrayList<>();
        play.get("qualities").forEach(q -> qs.add(q.get("quality").asText()));
        assertThat(qs).containsExactly("240p", "360p", "480p");
        assertThat(play.at("/qualities/1/default").asBoolean()).isTrue();
        assertThat(play.at("/qualities/1/perMinuteLabel").asText()).isEqualTo("about 2.3 MB a minute");
        assertThat(play.at("/qualities/0/sizeBytes").asLong()).isLessThan(play.at("/qualities/1/sizeBytes").asLong());
        assertThat(play.at("/audio/kind").asText()).isEqualTo("AUDIO_FILE");
        assertThat(play.at("/audio/url").asText()).startsWith("/api/v1/dev/storage/content/");
        assertThat(play.at("/audio/saving").asText()).isEqualTo("saves about 85% of the data");
        assertThat(play.get("speeds").size()).isEqualTo(4);
        assertThat(play.get("expiresAt").asText()).isNotBlank();
        assertThat(adeola.get(play.at("/audio/url").asText()).status()).as("the signed audio link works").isEqualTo(200);

        // 7 minutes at 1.5x, then she leaves: the place is kept.
        for (int t = 0; t < 420; t += 30) ok(adeola.post("/api/v1/content/me/items/" + videoId + "/progress",
                Map.of("positionSeconds", t + 30, "fromSeconds", t, "toSeconds", t + 30, "mode", "VIDEO", "speed", 1.5)), 200);
        assertThat(ok(adeola.get("/api/v1/content/me/items/" + videoId + "/playback"), 200).get("resumeAt").asInt()).isEqualTo(420);
        JsonNode cont = ok(adeola.get("/api/v1/content/me/continue"), 200);
        assertThat(cont.get("itemId").asText()).isEqualTo(videoId);
        assertThat(cont.get("label").asText()).isEqualTo("7 of 18 min");
        // Seeking to the end counts nothing.
        JsonNode jump = ok(adeola.post("/api/v1/content/me/items/" + videoId + "/progress", Map.of("positionSeconds", 1080, "fromSeconds", 1080, "toSeconds", 1080)), 200);
        assertThat(jump.get("done").asBoolean()).isFalse();
        // She switches to audio only for the rest (audio counts).
        JsonNode last = null;
        for (int t = 420; t < 1084; t += 30) last = ok(adeola.post("/api/v1/content/me/items/" + videoId + "/progress",
                Map.of("positionSeconds", Math.min(1084, t + 30), "fromSeconds", t, "toSeconds", Math.min(1084, t + 30), "mode", "AUDIO")), 200);
        assertThat(last.get("done").asBoolean()).isTrue();
        assertThat(last.get("percentWatched").asInt()).isGreaterThanOrEqualTo(90);
        assertThat(adeola.get("/api/v1/content/me/continue").status()).as("nothing unfinished").isEqualTo(204);

        // Notes and slides: opening counts; the page is marked done; the link is opened.
        JsonNode notes = ok(adeola.get("/api/v1/content/me/items/" + notesId), 200);
        assertThat(notes.at("/file/url").asText()).startsWith("/api/v1/dev/storage/content/");
        assertThat(notes.at("/file/contentType").asText()).isEqualTo("application/pdf");
        assertThat(adeola.get(notes.at("/file/url").asText()).body()).startsWith("%PDF-");
        assertThat(notes.at("/next/id").asText()).isEqualTo(slidesId);
        ok(adeola.post("/api/v1/content/me/items/" + notesId + "/open", Map.of()), 200);
        ok(adeola.post("/api/v1/content/me/items/" + slidesId + "/open", Map.of()), 200);
        JsonNode link = ok(adeola.get("/api/v1/content/me/items/" + linkId), 200);
        assertThat(link.get("warning").asText()).isEqualTo("This leaves the portal and uses your mobile data.");
        ok(adeola.post("/api/v1/content/me/items/" + linkId + "/open", Map.of()), 200);
        assertThat(ok(adeola.get("/api/v1/content/me/items/" + pageId), 200).get("body").asText()).startsWith("Bring two questions");
        assertThat(ok(adeola.post("/api/v1/content/me/items/" + pageId + "/done", Map.of()), 200).get("done").asBoolean()).isTrue();
        assertThat(error(adeola.post("/api/v1/content/me/items/" + videoId + "/done", Map.of()), 400)).isEqualTo("watch_it");

        JsonNode w5 = ok(adeola.get("/api/v1/content/me/weeks/" + week5), 200);
        assertThat(w5.get("progressLabel").asText()).isEqualTo("5 of 5 done");
        JsonNode page = ok(adeola.get("/api/v1/content/me/offerings/" + csc201), 200);
        assertThat(page.at("/progress/label").asText()).isEqualTo("1 of 3 weeks done · 33%");
        assertThat(page.at("/weeks/1/done").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ T-07, T-07b: Dr Bello sees her progress

    @Test
    @Order(8)
    void drBelloSeesAdeolasProgress() {
        JsonNode table = ok(belloB.get("/api/v1/content/offerings/" + csc201 + "/progress"), 200);
        assertThat(table.at("/counts/all").asInt()).isEqualTo(2);
        assertThat(table.at("/counts/notStarted").asInt()).isEqualTo(1);
        assertThat(table.get("videos").asInt()).isEqualTo(1);
        JsonNode a = null, e = null;
        for (JsonNode r : table.get("items")) {
            if (r.get("matricNumber").asText().equals("WU/ODL/24/0153")) a = r;
            if (r.get("matricNumber").asText().equals("WU/ODL/24/0219")) e = r;
        }
        assertThat(a.get("percent").asInt()).isEqualTo(33);
        assertThat(a.get("videosLabel").asText()).isEqualTo("1 of 1");
        assertThat(a.get("minutesWatched").asInt()).isEqualTo(18);
        assertThat(a.get("lastActiveLabel").asText()).isEqualTo("Today");
        assertThat(a.at("/centre/code").asText()).isEqualTo("AKR");
        assertThat(e.get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(e.get("lastActiveLabel").asText()).isEqualTo("Never");
        assertThat(ok(belloB.get("/api/v1/content/offerings/" + csc201 + "/progress?filter=NOT_STARTED"), 200).get("items").size()).isEqualTo(1);

        JsonNode detail = ok(belloB.get("/api/v1/content/offerings/" + csc201 + "/progress/" + adeolaId), 200);
        assertThat(detail.at("/weeks/1/done").asBoolean()).isTrue();
        assertThat(detail.at("/videos/0/title").asText()).isEqualTo("Lecture 5: Linked lists");
        assertThat(detail.at("/videos/0/minutesWatched").asInt()).isEqualTo(18);
        assertThat(error(belloB.get("/api/v1/content/offerings/" + csc201 + "/progress/" + chidiId), 404)).isEqualTo("not_found");

        Browser.Res csv = belloB.get("/api/v1/content/offerings/" + csc201 + "/progress.csv");
        assertThat(csv.body()).startsWith("student,matric_number,centre,percent").contains("Adeola Okafor,WU/ODL/24/0153,Akure Study Centre,33,1,1 of 1,18,");

        // The Akure coordinator sees Akure's students only, and cannot change anything.
        JsonNode akure = ok(adeB.get("/api/v1/content/offerings/" + csc201 + "/progress"), 200);
        assertThat(akure.get("items").size()).isEqualTo(1);
        assertThat(akure.at("/items/0/fullName").asText()).isEqualTo("Adeola Okafor");
        assertThat(error(adeB.post("/api/v1/content/offerings/" + csc201 + "/weeks", Map.of("title", "x")), 403)).isEqualTo("read_only");
    }

    // ------------------------------------------------------------------ T-05, L-13: announcement

    @Test
    @Order(9)
    void announcementReachesTheClass() throws Exception {
        JsonNode a = ok(belloB.post("/api/v1/content/offerings/" + csc201 + "/announcements", Map.of("title", "Week 5 slides are up; live class on Thursday",
                "body", "The slides and notes for week 5 are up. This week's live class is on Thursday at 6:00 pm.", "email", true)), 201);
        announcementId = a.get("id").asText();
        assertThat(a.get("message").asText()).isEqualTo("Sent to 2 students");
        assertThat(a.get("audienceLabel").asText()).isEqualTo("All 2 students");
        assertThat(a.at("/author/initials").asText()).isEqualTo("FB");
        JsonNode mail = email(adeolaEmail, "CSC 201: Week 5 slides are up", 30);
        assertThat(mail).isNotNull();
        assertThat(mail.get("body").asText()).contains("Dr Funmi Bello posted an announcement").contains("/courses/" + csc201 + "/announcements/");
        boolean bell = false;
        for (JsonNode n : ok(adeola.get("/api/v1/notifications"), 200).get("items")) bell |= n.get("title").asText().equals("CSC 201 · Week 5 slides are up; live class on Thursday");
        assertThat(bell).isTrue();
        assertThat(ok(chidi.get("/api/v1/notifications"), 200).toString()).doesNotContain("Week 5 slides are up");
        assertThat(ok(adeola.get("/api/v1/content/me/offerings/" + csc201), 200).at("/announcements/0/title").asText()).startsWith("Week 5 slides are up");

        JsonNode edited = ok(put(belloB, "/api/v1/content/announcements/" + announcementId, Map.of("title", "Week 5 slides are up; live class on Thursday",
                "body", "The slides and notes are up. Live class Thursday 6:00 pm; the recording will be up by Friday.")), 200);
        assertThat(edited.get("edited").asBoolean()).isTrue();
        assertThat(error(put(lawalB, "/api/v1/content/announcements/" + announcementId, Map.of("title", "x", "body", "y")), 403)).isEqualTo("not_your_course");
        assertThat(error(belloB.post("/api/v1/content/offerings/" + csc201 + "/announcements", Map.of("title", "Hi", "body", "x", "audience", "INACTIVE")), 409))
                .isEqualTo("no_students");
    }

    // ------------------------------------------------------------------ T-03e/k/m/l/f: item menu

    @Test
    @Order(10)
    void hideMoveReorderReplaceDelete() {
        ok(belloB.post("/api/v1/content/items/" + linkId + "/hide", Map.of()), 200);
        JsonNode w5 = ok(adeola.get("/api/v1/content/me/weeks/" + week5), 200);
        assertThat(item(w5, linkId)).as("hidden from students").isNull();
        assertThat(error(adeola.get("/api/v1/content/me/items/" + linkId), 404)).isEqualTo("not_found");
        assertThat(ok(belloB.post("/api/v1/content/items/" + linkId + "/show", Map.of()), 200).get("message").asText()).isEqualTo("Visible to students");

        JsonNode moved = ok(belloB.post("/api/v1/content/items/" + pageId + "/move", Map.of("weekId", week6)), 200);
        assertThat(moved.get("message").asText()).isEqualTo("Moved to Week 3");
        assertThat(error(belloB.delete("/api/v1/content/weeks/" + week6, null), 409)).isEqualTo("not_empty");
        ok(belloB.post("/api/v1/content/items/" + pageId + "/move", Map.of("weekId", week5)), 200);

        JsonNode w = ok(belloB.get("/api/v1/content/weeks/" + week5), 200);
        List<String> ids = new ArrayList<>();
        w.get("items").forEach(i -> ids.add(i.get("id").asText()));
        Collections.reverse(ids);
        JsonNode re = ok(put(belloB, "/api/v1/content/weeks/" + week5 + "/order", Map.of("itemIds", ids)), 200);
        assertThat(re.at("/items/0/id").asText()).isEqualTo(ids.get(0));
        assertThat(error(put(belloB, "/api/v1/content/weeks/" + week5 + "/order", Map.of("itemIds", ids.subList(1, ids.size()))), 400)).isEqualTo("order_mismatch");

        // Replace the notes: students keep the old file until the new one is in; same item, progress kept.
        String newer = PDF + "% a longer, corrected version\n";
        JsonNode rep = ok(belloB.post("/api/v1/content/items/" + notesId + "/replace", Map.of("fileName", "week-05-notes-v2.pdf", "sizeBytes", newer.length())), 200);
        assertThat(rep.at("/item/replacement/status").asText()).isEqualTo("UPLOADING");
        assertThat(belloB.send("PUT", rep.at("/upload/url").asText(), newer, "application/pdf").status()).isEqualTo(200);
        JsonNode swapped = ok(belloB.post("/api/v1/content/items/" + notesId + "/uploaded", Map.of()), 200);
        assertThat(swapped.get("id").asText()).isEqualTo(notesId);
        assertThat(swapped.at("/file/name").asText()).isEqualTo("week-05-notes-v2.pdf");
        assertThat(swapped.get("sizeBytes").asLong()).isEqualTo(newer.length());
        assertThat(swapped.has("replacement")).isFalse();
        assertThat(ok(adeola.get("/api/v1/content/me/items/" + notesId), 200).at("/item/progress/done").asBoolean()).as("her progress is kept").isTrue();
        assertThat(swapped.get("openedBy").asInt()).isEqualTo(1);

        // Delete the failed second video.
        ok(belloB.delete("/api/v1/content/items/" + video2Id, null), 204);
        assertThat(item(ok(belloB.get("/api/v1/content/weeks/" + week5), 200), video2Id)).isNull();
    }

    // ------------------------------------------------------------------ who may do what

    @Test
    @Order(11)
    void rolesAndUniversities() {
        assertThat(error(lawalB.post("/api/v1/content/offerings/" + csc201 + "/weeks", Map.of("title", "x")), 403)).isEqualTo("not_your_course");
        assertThat(error(lawalB.get("/api/v1/content/offerings/" + csc201 + "/progress"), 403)).isEqualTo("not_your_course");
        assertThat(ok(lawalB.get("/api/v1/content/courses"), 200).at("/items/0/course/code").asText()).isEqualTo("CSC 203");
        assertThat(yemiB.get("/api/v1/content/offerings/" + csc201).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/content/weeks/" + week5).status()).isEqualTo(404);
        assertThat(yemiB.post("/api/v1/content/items/" + videoId + "/hide", Map.of()).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/content/offerings/" + csc201 + "/announcements").status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ T-06, T-06b: copy last session's course

    @Test
    @Order(12)
    void copyLastSessionsCourse() {
        // Last session's CSC 203, built by the ODeL office.
        JsonNode sems = ok(ngoziB.get("/api/v1/content/courses"), 200).get("semesters");
        assertThat(sems.size()).isEqualTo(2);
        String lastSem = null;
        JsonNode all = ok(ngoziB.get("/api/v1/academic/sessions"), 200);
        for (JsonNode s : all.has("items") ? all.get("items") : all) {
            if (s.get("name").asText().equals(lastSessionName)) lastSem = s.at("/semesters/0/id").asText();
        }
        assertThat(lastSem).isNotNull();
        String old203 = null;
        for (JsonNode c : ok(ngoziB.get("/api/v1/content/courses?semesterId=" + lastSem), 200).get("items")) {
            if (c.at("/course/code").asText().equals("CSC 203")) old203 = c.get("offeringId").asText();
        }
        ok(put(ngoziB, "/api/v1/content/offerings/" + old203, Map.of("outline", "Processors, memory and buses.", "objectives", List.of("Explain pipelining"))), 200);
        for (int n = 1; n <= 3; n++) {
            String w = ok(ngoziB.post("/api/v1/content/offerings/" + old203 + "/weeks", Map.of("title", "Topic " + n, "opensOn", today.minusDays(400 - n * 7).toString())), 201)
                    .get("id").asText();
            ok(ngoziB.post("/api/v1/content/weeks/" + w + "/items", Map.of("kind", "PAGE", "title", "Reading " + n, "body", "Chapter " + n)), 201);
        }

        JsonNode over = ok(lawalB.get("/api/v1/content/offerings/" + csc203), 200);
        assertThat(over.get("canCopy").asBoolean()).isTrue();
        JsonNode sources = ok(lawalB.get("/api/v1/content/offerings/" + csc203 + "/copy-sources"), 200);
        assertThat(sources.at("/items/0/label").asText()).isEqualTo(lastSessionName + " · 1st semester");
        assertThat(sources.at("/items/0/weeks").asInt()).isEqualTo(3);
        JsonNode copied = ok(lawalB.post("/api/v1/content/offerings/" + csc203 + "/copy", Map.of()), 200);
        assertThat(copied.get("message").asText()).isEqualTo("Course copied · 3 weeks into CSC 203");
        assertThat(copied.get("items").asInt()).isEqualTo(3);
        JsonNode weeks = ok(lawalB.get("/api/v1/content/offerings/" + csc203 + "/weeks"), 200);
        assertThat(weeks.at("/items/0/statusLabel").asText()).isEqualTo("1 item · no open date");
        assertThat(ok(lawalB.get("/api/v1/content/offerings/" + csc203), 200).get("outline").asText()).isEqualTo("Processors, memory and buses.");
        assertThat(error(lawalB.post("/api/v1/content/offerings/" + csc203 + "/copy", Map.of()), 409)).isEqualTo("not_empty");

        JsonNode dated = ok(lawalB.post("/api/v1/content/offerings/" + csc203 + "/weeks/dates", Map.of("firstOpensOn", today.minusDays(7).toString(), "everyDays", 7)), 200);
        assertThat(dated.get("updated").asInt()).isEqualTo(3);
        JsonNode adeolas = ok(adeola.get("/api/v1/content/me/offerings/" + csc203), 200);
        assertThat(adeolas.get("empty").asBoolean()).isFalse();
        assertThat(adeolas.at("/weeks/0/open").asBoolean()).isTrue();
        assertThat(adeolas.at("/weeks/2/open").asBoolean()).isFalse();
    }
}
