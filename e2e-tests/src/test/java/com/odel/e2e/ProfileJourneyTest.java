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
 * B3 My profile and contact changes end-to-end (L-14 … L-14j and the ODeL office's review), through the api-gateway of a running
 * stack with the dev profile. A fresh Wesley (and Crestfield, for isolation): Adeola is on record as "Okafur" and activates.
 *
 * Journey: the profile page; phone saved at M-06 without a password, then only with it; address and next of kin; email changed
 * with a code sent to the new address (old address told, sign-in uses the new one); password changed (other browsers signed out);
 * recovery codes made and one used to reset the password; signed-in devices and sign out others; surname correction with proof
 * approved by Mrs Adebayo (the record, the account name and the history change); date-of-birth correction declined; a gender
 * request withdrawn; a new photo approved; another student and another university see nothing.
 *
 * Run: ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test -Dtest=ProfileJourneyTest
 */
@EnabledIfEnvironmentVariable(named = "ODEL_E2E_GATEWAY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProfileJourneyTest {
    final String gw = System.getenv("ODEL_E2E_GATEWAY");
    final String base = Optional.ofNullable(System.getenv("ODEL_E2E_BASE_DOMAIN")).orElse("localhost:3000");
    final String console = Optional.ofNullable(System.getenv("ODEL_E2E_CONSOLE_HOST")).orElse("console." + base);
    final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36) + Integer.toString(new Random().nextInt(1296), 36);
    final String wesleySlug = "wes-prof-" + run, crestSlug = "crest-prof-" + run;
    final String wesleyHost = wesleySlug + "." + base, crestHost = crestSlug + "." + base;
    final String wesleyDomain = "wes-prof-" + run + ".edu.ng", crestDomain = "crest-prof-" + run + ".edu.ng";
    final String ngozi = "ngozi.adebayo@" + wesleyDomain, yemi = "yemi.adeoye@" + crestDomain;
    final String studentMail = "students-prof-" + run + ".test";
    final String oldEmail = "adeola.okafor@" + studentMail, newEmail = "adeola.new@" + studentMail, bayoEmail = "bayo.adewale@" + studentMail;
    final Set<String> usedCodes = new HashSet<>();
    final Set<String> seenMails = new HashSet<>();
    final LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
    final int firstYear = today.getMonthValue() >= 8 ? today.getYear() : today.getYear() - 1;

    Browser chuka, ngoziB, yemiB, adeola, bayo;
    String adeolaId, bayoId, surnameRequestId;
    String password = "Student2026";

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
        u.put("name", name + " (" + slug + ")");
        u.put("shortName", name.split(" ")[0] + " ODeL");
        u.put("slug", slug);
        u.put("googleDomain", domain);
        u.put("firstAdmin", Map.of("fullName", admin, "email", adminEmail));
        u.put("branding", Map.of("primaryColor", "#1A2368", "accentColor", "#C27808"));
        Browser.Res created = chuka.post("/api/v1/platform/tenants", u);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.json().get("id").asText();
    }

    /** The newest delivered email to {@code to} whose subject contains {@code subjectPart} and that this test has not used yet. */
    JsonNode email(String to, String subjectPart) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            JsonNode list = chuka.get("/api/v1/dev/emails?to=" + URLEncoder.encode(to, StandardCharsets.UTF_8)).json();
            for (JsonNode e : list) {
                String key = e.has("id") ? e.get("id").asText() : e.get("body").asText();
                if (e.get("subject").asText().contains(subjectPart) && !e.get("status").asText().equals("QUEUED") && seenMails.add(key)) return e;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("No email \"" + subjectPart + "\" to " + to);
    }

    String sixDigits(JsonNode mail) {
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(mail.get("body").asText());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    static String created(Browser.Res r) {
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().get("id").asText();
    }

    static JsonNode ok(Browser.Res r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.body() == null || r.body().isBlank() ? null : r.json();
    }

    Browser.Res patch(Browser b, Object body) {
        return b.send("PATCH", "/api/v1/student-profile", body, "application/json");
    }

    String addStudent(String surname, String first, String matric, String email) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("surname", surname);
        s.put("firstName", first);
        s.put("matricNumber", matric);
        s.put("programmeCode", "BSC-CSC-ODL");
        s.put("level", 200);
        s.put("centreCode", "AKR");
        s.put("email", email);
        s.put("sendWelcome", false);
        return created(ngoziB.post("/api/v1/students", s));
    }

    Browser activate(String matric, String email) throws InterruptedException {
        Browser b = new Browser(gw, wesleyHost);
        b.get("/api/v1/public/context");
        String activationId = ok(b.post("/api/v1/auth/student/activation/start", Map.of("login", matric)), 200).get("activationId").asText();
        String code = sixDigits(email(email, "activation code"));
        String token = ok(b.post("/api/v1/auth/student/activation/verify", Map.of("activationId", activationId, "code", code)), 200).get("activationToken").asText();
        ok(b.post("/api/v1/auth/student/activation/password", Map.of("activationToken", token, "password", "Student2026", "confirmPassword", "Student2026")), 204);
        ok(b.post("/api/v1/auth/student/activation/complete", Map.of("activationToken", token, "acceptPrivacyNotice", true, "privacyNoticeVersion", "2026-11")), 200);
        return b;
    }

    Browser signIn(String login, String pw) {
        Browser b = new Browser(gw, wesleyHost);
        b.get("/api/v1/public/context");
        ok(b.post("/api/v1/auth/student/sign-in", Map.of("login", login, "password", pw)), 200);
        return b;
    }

    /** Asks for an upload link and PUTs the bytes; returns the key. */
    String upload(Browser b, String purpose, String fileName, String contentType, int size) {
        JsonNode link = ok(b.post("/api/v1/student-profile/uploads", Map.of("purpose", purpose, "fileName", fileName, "contentType", contentType, "size", size)), 200);
        assertThat(b.send("PUT", link.get("uploadUrl").asText(), "x".repeat(size), contentType).status()).isEqualTo(200);
        return link.get("key").asText();
    }

    JsonNode changes() {
        return ok(ngoziB.get("/api/v1/students/" + adeolaId + "/profile-changes?size=50"), 200).get("items");
    }

    /** The API leaves out null fields. */
    static boolean absent(JsonNode n, String field) {
        return n.path(field).isMissingNode() || n.path(field).isNull();
    }

    static boolean hasChange(JsonNode items, String field, String source) {
        for (JsonNode c : items) if (c.get("field").asText().equals(field) && c.get("source").asText().equals(source)) return true;
        return false;
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
        createUniversity("Wesley University", wesleySlug, wesleyDomain, "Ngozi Adebayo", ngozi);
        createUniversity("Crestfield University", crestSlug, crestDomain, "Yemi Adeoye", yemi);
        ngoziB = new Browser(gw, wesleyHost);
        firstSignIn(ngoziB, ngozi);
        yemiB = new Browser(gw, crestHost);
        firstSignIn(yemiB, yemi);

        String faculty = created(ngoziB.post("/api/v1/academic/faculties", Map.of("name", "Faculty of Science", "code", "SCI")));
        String dept = created(ngoziB.post("/api/v1/academic/departments", Map.of("facultyId", faculty, "name", "Computer Science", "code", "CSC")));
        created(ngoziB.post("/api/v1/academic/programmes", Map.of("departmentId", dept, "name", "B.Sc. Computer Science", "code", "BSC-CSC-ODL",
                "degree", "B.Sc.", "durationYears", 4, "entryLevels", List.of(100, 200))));
        created(ngoziB.post("/api/v1/academic/centres", Map.of("name", "Akure Study Centre", "code", "AKR", "address", "1 Oba Adesida Road", "state", "Ondo", "phone", "0803 555 0117")));
        LocalDate s1 = today.minusDays(60), e1 = today.plusDays(60), s2 = e1.plusDays(10), e2 = s2.plusDays(120);
        ok(ngoziB.post("/api/v1/academic/sessions", Map.of("name", firstYear + "/" + (firstYear + 1), "makeActive", false, "semesters", List.of(
                Map.of("number", 1, "startsOn", s1.toString(), "endsOn", e1.toString()),
                Map.of("number", 2, "startsOn", s2.toString(), "endsOn", e2.toString())))), 201);

        // On record as "Okafur": the portal file had the surname wrong.
        adeolaId = addStudent("Okafur", "Adeola", "WU/ODL/24/0153", oldEmail);
        bayoId = addStudent("Adewale", "Bayo", "WU/ODL/24/0688", bayoEmail);
        adeola = activate("WU/ODL/24/0153", oldEmail);
        bayo = activate("WU/ODL/24/0688", bayoEmail);
    }

    // ------------------------------------------------------------------ L-14

    @Test
    @Order(1)
    void profilePageShowsTheRecordAndTheSecurityCard() {
        JsonNode me = ok(adeola.get("/api/v1/student-profile"), 200);
        assertThat(me.get("matricNumber").asText()).isEqualTo("WU/ODL/24/0153");
        assertThat(me.get("surname").asText()).isEqualTo("Okafur");
        assertThat(me.get("maskedEmail").asText()).isEqualTo("adeola.o•••@" + studentMail);
        assertThat(me.get("programme").get("name").asText()).isEqualTo("B.Sc. Computer Science");
        assertThat(me.get("readOnly").get("centre").asText()).isEqualTo("Only the ODeL office can change this");
        assertThat(absent(me, "address")).isTrue();
        assertThat(absent(me, "photo")).isTrue();
        assertThat(me.get("openRequests")).isEmpty();

        JsonNode sec = ok(adeola.get("/api/v1/me/security"), 200);
        assertThat(absent(sec, "passwordChangedAt")).isFalse();
        assertThat(absent(sec, "recoveryCodes")).isTrue();
        assertThat(sec.get("sessions").get("count").asInt()).isEqualTo(1);
        assertThat(sec.get("sessions").get("summary").asText()).isEqualTo("This browser only");

        // Staff do not use the student's account screens.
        assertThat(ngoziB.get("/api/v1/me/security").status()).isEqualTo(403);
        assertThat(ngoziB.get("/api/v1/student-profile").status()).isEqualTo(403);
    }

    @Test
    @Order(2)
    void phoneNeedsThePasswordAfterTheFirstSave() throws Exception {
        // M-06 right after activation: the first save needs no password.
        assertThat(ok(patch(adeola, Map.of("phone", "0803 412 4412")), 200).get("maskedPhone").asText()).isEqualTo("0803 *** 4412");
        email(oldEmail, "Your contact details were changed");

        // L-14b afterwards: the password is asked for.
        Browser.Res noPw = patch(adeola, Map.of("phone", "0816 555 2290"));
        assertThat(noPw.status()).as(noPw.body()).isEqualTo(400);
        assertThat(noPw.json().at("/fields/password").asText()).isEqualTo("Enter your password to confirm it is you.");
        Browser.Res wrong = patch(adeola, Map.of("phone", "0816 555 2290", "password", "Wrong2026"));
        assertThat(wrong.status()).as(wrong.body()).isEqualTo(401);
        assertThat(wrong.json().get("error").asText()).isEqualTo("wrong_password");
        assertThat(wrong.json().get("attemptsLeft").asInt()).isEqualTo(4);
        assertThat(patch(adeola, Map.of("phone", "12345", "password", password)).json().at("/fields/phone").asText()).contains("Nigerian mobile number");

        JsonNode saved = ok(patch(adeola, Map.of("phone", "0816 555 2290", "password", password)), 200);
        assertThat(saved.get("phone").asText()).isEqualTo("0816 555 2290");
        assertThat(saved.get("maskedPhone").asText()).isEqualTo("0816 *** 2290");
        JsonNode mail = email(oldEmail, "Your contact details were changed");
        assertThat(mail.get("body").asText()).contains("Phone number: 0816 *** 2290").contains("If this was not you");
        Browser.Res same = patch(adeola, Map.of("phone", "0816 555 2290", "password", password));
        assertThat(same.status()).isEqualTo(400);
        assertThat(same.json().get("error").asText()).isEqualTo("no_change");

        assertThat(hasChange(changes(), "PHONE", "STUDENT")).isTrue();
        // The wrong password did not lock her: the right one cleared the count.
        ok(signIn("WU/ODL/24/0153", password).get("/api/v1/session"), 200);
    }

    @Test
    @Order(3)
    void addressAndNextOfKin() throws Exception {
        Browser.Res bad = patch(adeola, Map.of("address", Map.of("line", "14 Oyemekun Road", "town", "Akure", "state", "Accra")));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().at("/fields/address.state").asText()).isEqualTo("Choose your state.");
        JsonNode me = ok(patch(adeola, Map.of("address", Map.of("line", "14 Oyemekun Road", "town", "Akure", "state", "ondo state"),
                "nextOfKin", Map.of("name", "Mrs Chioma Okafor", "relationship", "Mother", "phone", "08051112233"))), 200);
        assertThat(me.get("address").get("state").asText()).isEqualTo("Ondo");
        assertThat(me.get("nextOfKin").get("phone").asText()).isEqualTo("0805 111 2233");
        assertThat(email(oldEmail, "Your contact details were changed").get("body").asText())
                .contains("Home address: 14 Oyemekun Road, Akure, Ondo").contains("Next of kin: Mrs Chioma Okafor (Mother)");
        JsonNode record = ok(ngoziB.get("/api/v1/students/" + adeolaId), 200);
        assertThat(record.get("address").get("town").asText()).isEqualTo("Akure");
        assertThat(record.get("nextOfKin").get("name").asText()).isEqualTo("Mrs Chioma Okafor");
        JsonNode ch = changes();
        assertThat(hasChange(ch, "ADDRESS", "STUDENT")).isTrue();
        assertThat(hasChange(ch, "NEXT_OF_KIN", "STUDENT")).isTrue();
    }

    // ------------------------------------------------------------------ L-14d, L-14e

    @Test
    @Order(4)
    void emailChangedWithACodeSentToTheNewAddress() throws Exception {
        assertThat(adeola.post("/api/v1/me/email-change", Map.of("newEmail", newEmail, "password", "Wrong2026")).status()).isEqualTo(401);
        Browser.Res taken = adeola.post("/api/v1/me/email-change", Map.of("newEmail", bayoEmail, "password", password));
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.json().get("error").asText()).isEqualTo("email_taken");
        assertThat(adeola.post("/api/v1/me/email-change", Map.of("newEmail", oldEmail.toUpperCase(), "password", password)).json().get("error").asText())
                .isEqualTo("same_email");
        assertThat(adeola.post("/api/v1/me/email-change", Map.of("newEmail", "not-an-email", "password", password)).status()).isEqualTo(400);

        JsonNode started = ok(adeola.post("/api/v1/me/email-change", Map.of("newEmail", newEmail, "password", password)), 200);
        assertThat(started.get("codeSent").asBoolean()).isTrue();
        assertThat(started.get("maskedEmail").asText()).isEqualTo("a•••••@" + studentMail);
        String changeId = started.get("changeId").asText();
        String code = sixDigits(email(newEmail, "Your code to confirm this email"));
        // Nothing has changed yet: she still signs in with the old address.
        ok(signIn(oldEmail, password).get("/api/v1/session"), 200);

        String wrongCode = code.equals("000000") ? "111111" : "000000";
        Browser.Res wrong = adeola.post("/api/v1/me/email-change/verify", Map.of("changeId", changeId, "code", wrongCode));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.json().get("attemptsLeft").asInt()).isEqualTo(2);
        assertThat(adeola.post("/api/v1/me/email-change/resend", Map.of("changeId", changeId)).status()).isEqualTo(429);   // within 60 seconds
        // Bayo cannot finish her change.
        assertThat(bayo.post("/api/v1/me/email-change/verify", Map.of("changeId", changeId, "code", code)).status()).isEqualTo(410);

        JsonNode done = ok(adeola.post("/api/v1/me/email-change/verify", Map.of("changeId", changeId, "code", code)), 200);
        assertThat(done.get("email").asText()).isEqualTo(newEmail);
        assertThat(email(oldEmail, "Your email address was changed").get("body").asText()).contains("If this was not you");
        assertThat(email(newEmail, "This is now your email").get("body").asText()).contains("From now on");
        assertThat(ok(adeola.get("/api/v1/student-profile"), 200).get("email").asText()).isEqualTo(newEmail);
        assertThat(ok(ngoziB.get("/api/v1/students/" + adeolaId), 200).get("email").asText()).isEqualTo(newEmail);
        ok(signIn(newEmail, password).get("/api/v1/session"), 200);
        Browser.Res oldLogin = new Browser(gw, wesleyHost).post("/api/v1/auth/student/sign-in", Map.of("login", oldEmail, "password", password));
        assertThat(oldLogin.status()).isEqualTo(404);
        assertThat(hasChange(changes(), "EMAIL", "STUDENT")).isTrue();
    }

    // ------------------------------------------------------------------ L-14f, L-14g, L-14j

    @Test
    @Order(5)
    void passwordChangeSignsOutOtherBrowsers() throws Exception {
        Browser phone = signIn("WU/ODL/24/0153", password);
        Browser.Res wrong = adeola.post("/api/v1/me/password", Map.of("currentPassword", "Wrong2026", "newPassword", "Newpass2027", "confirmPassword", "Newpass2027"));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.json().at("/fields/currentPassword").asText()).startsWith("That password is not right.");
        Browser.Res mismatch = adeola.post("/api/v1/me/password", Map.of("currentPassword", password, "newPassword", "Newpass2027", "confirmPassword", "Newpass2028"));
        assertThat(mismatch.json().at("/fields/confirmPassword").asText()).isEqualTo("The two passwords do not match.");
        assertThat(adeola.post("/api/v1/me/password", Map.of("currentPassword", password, "newPassword", password, "confirmPassword", password))
                .json().get("error").asText()).isEqualTo("same_password");

        ok(adeola.post("/api/v1/me/password", Map.of("currentPassword", password, "newPassword", "Newpass2027", "confirmPassword", "Newpass2027")), 204);
        password = "Newpass2027";
        assertThat(phone.get("/api/v1/session").status()).as("other browsers end").isEqualTo(401);
        ok(adeola.get("/api/v1/session"), 200);
        assertThat(email(newEmail, "Your password was changed").get("body").asText()).contains("signed you out of");
        ok(signIn("WU/ODL/24/0153", password).get("/api/v1/session"), 200);
    }

    @Test
    @Order(6)
    void recoveryCodesResetThePasswordWithoutEmail() throws Exception {
        assertThat(ok(adeola.get("/api/v1/me/recovery-codes"), 200).get("total").asInt()).isZero();
        assertThat(adeola.post("/api/v1/me/recovery-codes", Map.of("password", "Wrong2026")).status()).isEqualTo(401);
        JsonNode made = ok(adeola.post("/api/v1/me/recovery-codes", Map.of("password", password)), 200);
        assertThat(made.get("codes")).hasSize(8);
        String first = made.get("codes").get(0).asText();
        assertThat(first).matches("\\d{4}-\\d{4}");
        JsonNode again = ok(adeola.get("/api/v1/me/recovery-codes"), 200);
        assertThat(again.get("left").asInt()).isEqualTo(8);
        assertThat(absent(again, "codes")).isTrue();   // shown once
        email(newEmail, "You made new recovery codes");

        Browser locked = new Browser(gw, wesleyHost);
        Browser.Res bad = locked.post("/api/v1/auth/student/password-reset/recovery", Map.of("login", "WU/ODL/24/0153", "recoveryCode", "0000-0000"));
        assertThat(bad.status()).as(bad.body()).isEqualTo(401);
        assertThat(bad.json().get("error").asText()).isEqualTo("wrong_recovery_code");
        assertThat(locked.post("/api/v1/auth/student/password-reset/recovery", Map.of("login", "WU/ODL/24/0688", "recoveryCode", first)).json().get("error").asText())
                .as("Bayo has no codes").isEqualTo("no_recovery_codes");
        JsonNode reset = ok(locked.post("/api/v1/auth/student/password-reset/recovery", Map.of("login", "WU/ODL/24/0153", "recoveryCode", first)), 200);
        assertThat(reset.get("codesLeft").asInt()).isEqualTo(7);
        ok(locked.post("/api/v1/auth/student/password-reset/complete", Map.of("resetToken", reset.get("resetToken").asText(),
                "password", "Recovered2027", "confirmPassword", "Recovered2027")), 200);
        password = "Recovered2027";
        assertThat(email(newEmail, "A recovery code was used").get("body").asText()).contains("7 codes left");
        // The same code does not work twice.
        assertThat(new Browser(gw, wesleyHost).post("/api/v1/auth/student/password-reset/recovery", Map.of("login", "WU/ODL/24/0153", "recoveryCode", first)).status())
                .isEqualTo(401);
        // The reset ended her other sessions; she continues in the browser that reset it.
        adeola = locked;
        assertThat(ok(adeola.get("/api/v1/me/recovery-codes"), 200).get("left").asInt()).isEqualTo(7);
    }

    @Test
    @Order(7)
    void signedInDevicesAndSignOutOthers() {
        Browser second = signIn("WU/ODL/24/0153", password);
        Browser third = signIn("WU/ODL/24/0153", password);
        JsonNode list = ok(adeola.get("/api/v1/me/sessions"), 200);
        assertThat(list.get("items")).hasSize(3);
        assertThat(list.get("items").get(0).get("current").asBoolean()).isTrue();
        assertThat(list.get("summary").asText()).isEqualTo("This browser and 2 other devices");
        String secondId = null;
        for (JsonNode s : ok(second.get("/api/v1/me/sessions"), 200).get("items")) if (s.get("current").asBoolean()) secondId = s.get("id").asText();
        ok(adeola.delete("/api/v1/me/sessions/" + secondId, null), 204);
        assertThat(second.get("/api/v1/session").status()).isEqualTo(401);
        String mine = list.get("items").get(0).get("id").asText();
        assertThat(adeola.delete("/api/v1/me/sessions/" + mine, null).status()).as("not this browser").isEqualTo(404);
        assertThat(ok(adeola.post("/api/v1/me/sessions/sign-out-others", Map.of()), 200).get("signedOut").asInt()).isEqualTo(1);
        assertThat(third.get("/api/v1/session").status()).isEqualTo(401);
        assertThat(ok(adeola.get("/api/v1/me/sessions"), 200).get("summary").asText()).isEqualTo("This browser only");
        // Staff can see their own devices too.
        ok(ngoziB.get("/api/v1/me/sessions"), 200);
    }

    // ------------------------------------------------------------------ L-14i and the office

    @Test
    @Order(8)
    void surnameCorrectionApprovedByTheOffice() throws Exception {
        JsonNode fields = ok(adeola.get("/api/v1/student-profile/correction-fields"), 200);
        assertThat(fields.get(0).get("label").asText()).isEqualTo("Surname spelling");
        assertThat(fields.get(0).get("current").asText()).isEqualTo("Okafur");

        Browser.Res noProof = adeola.post("/api/v1/student-profile/corrections", Map.of("field", "SURNAME", "requestedValue", "Okafor"));
        assertThat(noProof.status()).isEqualTo(400);
        assertThat(noProof.json().at("/fields/proof").asText()).contains("proof is required");
        assertThat(adeola.post("/api/v1/student-profile/uploads", Map.of("purpose", "PROOF", "fileName", "letter.docx", "size", 1000)).json()
                .at("/fields/fileName").asText()).isEqualTo("Attach a JPG, PNG or PDF file.");
        String bayoKey = upload(bayo, "PROOF", "x.pdf", "application/pdf", 1000);
        Browser.Res foreign = adeola.post("/api/v1/student-profile/corrections", Map.of("field", "SURNAME", "requestedValue", "Okafor",
                "proof", Map.of("key", bayoKey)));
        assertThat(foreign.json().at("/fields/proof").asText()).isEqualTo("Upload the file again.");
        String key = upload(adeola, "PROOF", "birth-certificate.pdf", "application/pdf", 4096);
        assertThat(adeola.post("/api/v1/student-profile/corrections", Map.of("field", "SURNAME", "requestedValue", "Okafur",
                "proof", Map.of("key", key))).json().get("error").asText()).isEqualTo("no_change");

        JsonNode req = ok(adeola.post("/api/v1/student-profile/corrections", Map.of("field", "SURNAME", "requestedValue", "Okafor",
                "note", "My JAMB letter and birth certificate both say Okafor.",
                "proof", Map.of("key", key, "fileName", "birth-certificate.pdf", "contentType", "application/pdf", "size", 4096))), 201);
        surnameRequestId = req.get("id").asText();
        assertThat(req.get("status").asText()).isEqualTo("PENDING");
        assertThat(req.get("statusLabel").asText()).isEqualTo("Waiting for the ODeL office");
        assertThat(req.get("proof").get("type").asText()).isEqualTo("PDF");
        assertThat(adeola.post("/api/v1/student-profile/corrections", Map.of("field", "SURNAME", "requestedValue", "Okaforr",
                "proof", Map.of("key", key))).status()).isEqualTo(409);
        assertThat(email(newEmail, "We got your request").get("body").asText()).contains("the correction to your surname");
        assertThat(ok(adeola.get("/api/v1/student-profile"), 200).get("openRequests")).hasSize(1);

        // The office: a bell, the list, the proof, approve.
        boolean bell = false;
        for (JsonNode n : ok(ngoziB.get("/api/v1/notifications"), 200).get("items")) bell |= n.get("type").asText().equals("staff.profile_request_new");
        assertThat(bell).isTrue();
        JsonNode list = ok(ngoziB.get("/api/v1/students/profile-requests"), 200);
        assertThat(list.get("counts").get("pending").asInt()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("student").get("matricNumber").asText()).isEqualTo("WU/ODL/24/0153");
        assertThat(ok(ngoziB.get("/api/v1/students/profile-requests?q=okafur"), 200).get("total").asInt()).isEqualTo(1);
        assertThat(ok(ngoziB.get("/api/v1/students/profile-requests?q=bayo"), 200).get("total").asInt()).isZero();
        assertThat(ok(ngoziB.get("/api/v1/students/profile-requests/" + surnameRequestId + "/proof"), 200).get("url").asText()).isNotBlank();
        assertThat(ngoziB.post("/api/v1/students/profile-requests/" + surnameRequestId + "/decline", Map.of("note", "no")).status()).isEqualTo(400);

        JsonNode approved = ok(ngoziB.post("/api/v1/students/profile-requests/" + surnameRequestId + "/approve", Map.of()), 200);
        assertThat(approved.get("request").get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("request").get("decision").get("by").asText()).isEqualTo("Ngozi Adebayo");
        Browser.Res twice = ngoziB.post("/api/v1/students/profile-requests/" + surnameRequestId + "/approve", Map.of());
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.json().get("message").asText()).isEqualTo("This request was already approved by Ngozi Adebayo.");

        assertThat(ok(adeola.get("/api/v1/student-profile"), 200).get("surname").asText()).isEqualTo("Okafor");
        assertThat(ok(adeola.get("/api/v1/session"), 200).toString()).contains("Adeola Okafor");
        assertThat(email(newEmail, "Your request was approved").get("body").asText()).contains("Your surname is now Okafor");
        JsonNode ch = changes();
        assertThat(hasChange(ch, "SURNAME", "REQUEST")).isTrue();
        for (JsonNode c : ch) if (c.get("field").asText().equals("SURNAME")) {
            assertThat(c.get("oldValue").asText()).isEqualTo("Okafur");
            assertThat(c.get("by").get("fullName").asText()).isEqualTo("Ngozi Adebayo");
        }
    }

    @Test
    @Order(9)
    void dateOfBirthDeclinedAndGenderWithdrawn() throws Exception {
        assertThat(adeola.post("/api/v1/student-profile/corrections", Map.of("field", "DATE_OF_BIRTH", "requestedValue", today.minusYears(5).toString(),
                "proof", Map.of("key", "x"))).json().at("/fields/requestedValue").asText()).startsWith("Check the year");
        String key = upload(adeola, "PROOF", "birth.jpg", "image/jpeg", 2048);
        String dob = ok(adeola.post("/api/v1/student-profile/corrections", Map.of("field", "DATE_OF_BIRTH", "requestedValue", "2003-04-17",
                "proof", Map.of("key", key))), 201).get("id").asText();
        JsonNode declined = ok(ngoziB.post("/api/v1/students/profile-requests/" + dob + "/decline",
                Map.of("note", "The birth certificate photo is not readable. Upload a clearer copy.")), 200);
        assertThat(declined.get("request").get("status").asText()).isEqualTo("DECLINED");
        assertThat(email(newEmail, "Your request was not approved").get("body").asText()).contains("not readable");
        assertThat(absent(ok(adeola.get("/api/v1/student-profile"), 200), "dateOfBirth")).isTrue();

        String key2 = upload(adeola, "PROOF", "nin.png", "image/png", 2048);
        String gender = ok(adeola.post("/api/v1/student-profile/corrections", Map.of("field", "GENDER", "requestedValue", "female",
                "proof", Map.of("key", key2))), 201).get("id").asText();
        assertThat(ok(adeola.post("/api/v1/student-profile/requests/" + gender + "/withdraw", Map.of()), 200).get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(adeola.post("/api/v1/student-profile/requests/" + gender + "/withdraw", Map.of()).status()).isEqualTo(409);
        assertThat(ngoziB.post("/api/v1/students/profile-requests/" + gender + "/approve", Map.of()).json().get("message").asText())
                .isEqualTo("This request was withdrawn.");
        JsonNode mine = ok(adeola.get("/api/v1/student-profile/requests"), 200).get("items");
        assertThat(mine).hasSize(3);
    }

    @Test
    @Order(10)
    void newPhotoWaitsForTheOffice() throws Exception {
        assertThat(adeola.post("/api/v1/student-profile/uploads", Map.of("purpose", "PHOTO", "fileName", "me.jpg", "size", 3_000_000)).json()
                .at("/fields/size").asText()).isEqualTo("The photo is over 2 MB. Choose a smaller one.");
        assertThat(adeola.post("/api/v1/student-profile/uploads", Map.of("purpose", "PHOTO", "fileName", "me.pdf", "size", 1000)).status()).isEqualTo(400);
        String key = upload(adeola, "PHOTO", "me.jpg", "image/jpeg", 5000);
        String reqId = ok(adeola.post("/api/v1/student-profile/photo", Map.of("key", key)), 201).get("id").asText();
        JsonNode me = ok(adeola.get("/api/v1/student-profile"), 200);
        assertThat(absent(me, "photo")).isTrue();
        assertThat(me.get("pendingPhoto").get("requestId").asText()).isEqualTo(reqId);
        JsonNode detail = ok(ngoziB.get("/api/v1/students/profile-requests/" + reqId), 200);
        assertThat(detail.get("newPhotoUrl").asText()).isNotBlank();
        assertThat(ok(ngoziB.get("/api/v1/students/profile-requests?kind=PHOTO"), 200).get("total").asInt()).isEqualTo(1);
        ok(ngoziB.post("/api/v1/students/profile-requests/" + reqId + "/approve", Map.of("note", "Clear photo, thank you.")), 200);
        me = ok(adeola.get("/api/v1/student-profile"), 200);
        assertThat(me.get("photo").get("url").asText()).isNotBlank();
        assertThat(absent(me, "pendingPhoto")).isTrue();
        assertThat(absent(ok(ngoziB.get("/api/v1/students/" + adeolaId), 200), "photo")).isFalse();
        assertThat(hasChange(changes(), "PHOTO", "REQUEST")).isTrue();
    }

    @Test
    @Order(11)
    void officeEditsAreInTheHistoryAndTheStudentIsTold() throws Exception {
        ok(ngoziB.send("PATCH", "/api/v1/students/" + adeolaId, Map.of("phone", "0809 000 1111"), "application/json"), 200);
        assertThat(email(newEmail, "Your contact details were changed").get("body").asText()).contains("by the ODeL office").contains("0809 *** 1111");
        assertThat(hasChange(changes(), "PHONE", "OFFICE")).isTrue();
    }

    @Test
    @Order(12)
    void anotherStudentAndAnotherUniversitySeeNothing() {
        assertThat(bayo.get("/api/v1/student-profile/requests/" + surnameRequestId).status()).isEqualTo(404);
        assertThat(bayo.get("/api/v1/student-profile/requests/" + surnameRequestId + "/proof").status()).isEqualTo(404);
        assertThat(bayo.post("/api/v1/student-profile/requests/" + surnameRequestId + "/withdraw", Map.of()).status()).isEqualTo(404);
        assertThat(bayo.get("/api/v1/students/profile-requests").status()).isEqualTo(403);
        assertThat(yemiB.get("/api/v1/students/profile-requests/" + surnameRequestId).status()).isEqualTo(404);
        assertThat(yemiB.post("/api/v1/students/profile-requests/" + surnameRequestId + "/decline", Map.of("note", "Not readable, sorry.")).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/students/" + adeolaId + "/profile-changes").status()).isEqualTo(404);
        assertThat(ok(yemiB.get("/api/v1/students/profile-requests?status=ALL"), 200).get("total").asInt()).isZero();
    }
}
