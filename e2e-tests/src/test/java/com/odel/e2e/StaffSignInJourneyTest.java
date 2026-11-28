package com.odel.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff sign-in per university (owner's decision 28 Nov 2026), end to end through the api-gateway with the dev profile:
 * the provider adds one university for each method (Google Workspace, Microsoft 365, Email code) and each first admin
 * signs in with it, then the authenticator two-step. Negatives: another university's method is refused, wrong domain,
 * an unknown address gets exactly the same answer as a staff member's, three wrong codes send a new code, an expired
 * code, a suspended account. Last, the provider switches a university's method: the change is audited and the same
 * account signs in the new way with its existing two-step.
 *
 * Run: ODEL_E2E_GATEWAY=http://localhost:9300 mvn -o -pl e2e-tests test -Dtest=StaffSignInJourneyTest
 * The provider admin must be able to sign in: either two-step not yet set up, or ODEL_E2E_PROVIDER_TOTP_SECRET set.
 */
@EnabledIfEnvironmentVariable(named = "ODEL_E2E_GATEWAY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaffSignInJourneyTest {
    final String gw = System.getenv("ODEL_E2E_GATEWAY");
    final String base = Optional.ofNullable(System.getenv("ODEL_E2E_BASE_DOMAIN")).orElse("localhost:3000");
    final String console = Optional.ofNullable(System.getenv("ODEL_E2E_CONSOLE_HOST")).orElse("console." + base);
    final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36);
    final String gSlug = "goog-" + run, mSlug = "msft-" + run, eSlug = "mail-" + run;
    final String gHost = gSlug + "." + base, mHost = mSlug + "." + base, eHost = eSlug + "." + base;
    final String gDomain = gSlug + ".edu.ng", mDomain = mSlug + ".edu.ng", eDomain = eSlug + ".edu.ng";
    final String gAdmin = "ngozi.adebayo@" + gDomain, mAdmin = "ngozi.adebayo@" + mDomain, eAdmin = "ngozi.adebayo@" + eDomain;
    final String eStaff = "b.ade@" + eDomain;

    Browser chuka;
    String chukaSecret, mId, eId, mAdminSecret;
    final Set<String> usedCodes = new HashSet<>();

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

    /** After the first step: set up the authenticator and finish signing in. */
    String setUpTwoStep(Browser b) {
        assertThat(b.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_SETUP");
        String secret = b.post("/api/v1/auth/mfa/setup", Map.of()).json().get("secret").asText();
        Browser.Res confirmed = b.post("/api/v1/auth/mfa/setup/confirm", Map.of("code", code(secret)));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.json().at("/session/stage").asText()).isEqualTo("SIGNED_IN");
        return secret;
    }

    JsonNode ok(Browser.Res r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.body() == null || r.body().isBlank() ? null : r.json();
    }

    Map<String, Object> university(String name, String slug, String domain, String method, String adminEmail) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", name + " (" + slug + ")");
        u.put("shortName", name + " ODeL");
        u.put("slug", slug);
        u.put("staffEmailDomain", domain);
        if (method != null) u.put("staffSignIn", method);
        u.put("firstAdmin", Map.of("fullName", "Ngozi Adebayo", "email", adminEmail));
        u.put("branding", Map.of("primaryColor", "#1A2368", "accentColor", "#C27808"));
        return u;
    }

    /** Newest emails to this address (dev email log; platform admin only). */
    List<JsonNode> emailsTo(String address) {
        List<JsonNode> out = new ArrayList<>();
        chuka.get("/api/v1/dev/emails?to=" + address).json().forEach(out::add);
        return out;
    }

    static final Pattern SIX = Pattern.compile("(\\d{6})$");

    /** The newest sign-in code emailed to this address. */
    String latestCode(String address) {
        for (JsonNode e : emailsTo(address)) {
            Matcher m = SIX.matcher(e.get("subject").asText());
            if (e.get("subject").asText().contains("staff sign-in code") && m.find()) return m.group(1);
        }
        throw new IllegalStateException("No sign-in code emailed to " + address);
    }

    List<String> auditSummaries(String tenantId) {
        List<String> out = new ArrayList<>();
        chuka.get("/api/v1/platform/audit?size=200&tenantId=" + tenantId).json().get("items")
                .forEach(i -> out.add(i.get("action").asText() + " | " + i.get("summary").asText()));
        return out;
    }

    // ---- the journey ----

    @Test
    @Order(1)
    void providerAddsAUniversityForEachMethod() {
        chuka = new Browser(gw, console);
        String chukaEmail = Optional.ofNullable(System.getenv("ODEL_E2E_PROVIDER_EMAIL")).orElse("chuka.obi@odel.dev");
        assertThat(chuka.googleSignIn(chukaEmail)).startsWith("/auth/continue?next=");
        if (chuka.get("/api/v1/session").json().get("stage").asText().equals("MFA_SETUP")) {
            chukaSecret = setUpTwoStep(chuka);
        } else {
            chukaSecret = System.getenv("ODEL_E2E_PROVIDER_TOTP_SECRET");
            Assumptions.assumeTrue(chukaSecret != null, "provider already has two-step; set ODEL_E2E_PROVIDER_TOTP_SECRET");
            ok(chuka.post("/api/v1/auth/mfa/verify", Map.of("code", code(chukaSecret))), 200);
        }
        // The console itself stays on Google; Microsoft and email codes are refused there.
        Browser consoleOther = new Browser(gw, console);
        assertThat(consoleOther.microsoftSignIn(chukaEmail)).isEqualTo("/sign-in?error=wrong_method");
        assertThat(consoleOther.get("/api/v1/public/context").json().at("/tenant/staffSignIn").asText()).isEqualTo("GOOGLE");

        // Validation: an unknown method, a bad domain (reported under the name the client used).
        Map<String, Object> bad = university("Bad", "bad-" + run, "not a domain", "ZOHO", "x@" + eDomain);
        JsonNode badBody = ok(chuka.post("/api/v1/platform/tenants", bad), 400);
        assertThat(badBody.at("/fields/staffSignIn").asText()).contains("Google Workspace, Microsoft 365 or Email code");
        assertThat(badBody.at("/fields/staffEmailDomain").asText()).contains("staff email domain");

        // Google: the method left out = Google (older console builds send no staffSignIn).
        JsonNode g = ok(chuka.post("/api/v1/platform/tenants", university("Goog University", gSlug, gDomain, null, gAdmin)), 201);
        assertThat(g.get("staffSignIn").asText()).isEqualTo("GOOGLE");
        assertThat(g.get("staffSignInLabel").asText()).isEqualTo("Google Workspace");
        assertThat(g.get("staffEmailDomain").asText()).isEqualTo(gDomain);
        assertThat(g.get("googleDomain").asText()).isEqualTo(gDomain); // old name still answered

        JsonNode m = ok(chuka.post("/api/v1/platform/tenants", university("Msft University", mSlug, mDomain, "MICROSOFT", mAdmin)), 201);
        mId = m.get("id").asText();
        assertThat(m.get("staffSignInLabel").asText()).isEqualTo("Microsoft 365");
        JsonNode e = ok(chuka.post("/api/v1/platform/tenants", university("Mail University", eSlug, eDomain, "EMAIL_CODE", eAdmin)), 201);
        eId = e.get("id").asText();
        assertThat(e.get("staffSignInLabel").asText()).isEqualTo("Email code");

        // Each portal brands its sign-in with its method; the invitations say how to sign in, not "Google".
        assertThat(new Browser(gw, mHost).get("/api/v1/public/context").json().at("/tenant/staffSignIn").asText()).isEqualTo("MICROSOFT");
        JsonNode eCtx = new Browser(gw, eHost).get("/api/v1/public/context").json().get("tenant");
        assertThat(eCtx.get("staffSignIn").asText()).isEqualTo("EMAIL_CODE");
        assertThat(eCtx.get("staffEmailDomain").asText()).isEqualTo(eDomain);
        String mInvite = emailsTo(mAdmin).get(0).get("body").asText();
        assertThat(mInvite).contains("Sign in with your Microsoft work account (your @" + mDomain + " address) here:").doesNotContain("Google");
        String eInvite = emailsTo(eAdmin).get(0).get("body").asText();
        assertThat(eInvite).contains("Sign in with a code sent to your work email (your @" + eDomain + " address) here:").doesNotContain("Google");
        assertThat(emailsTo(gAdmin).get(0).get("body").asText()).contains("Sign in with your Google work account");
    }

    @Test
    @Order(2)
    void googleUniversitySignsInWithGoogleOnly() {
        Browser b = new Browser(gw, gHost);
        assertThat(b.microsoftSignIn(gAdmin)).isEqualTo("/staff/sign-in?error=wrong_method");
        assertThat(ok(b.post("/api/v1/auth/email-code/start", Map.of("email", gAdmin)), 409).get("error").asText()).isEqualTo("wrong_method");
        assertThat(b.googleSignIn(gAdmin)).startsWith("/auth/continue?next=");
        setUpTwoStep(b);
    }

    @Test
    @Order(3)
    void microsoftUniversitySignsInThroughTheMicrosoftStandIn() {
        Browser b = new Browser(gw, mHost);
        // Google is not this university's method.
        assertThat(b.googleSignIn(mAdmin)).isEqualTo("/staff/sign-in?error=wrong_method");
        // The dev page is labelled as the Microsoft stand-in.
        Browser.Res start = b.get("/api/v1/auth/microsoft/start?returnTo=/staff");
        assertThat(start.location()).startsWith("/api/v1/auth/dev/microsoft?state=");
        assertThat(b.get(start.location()).body()).contains("Microsoft stand-in").contains("Microsoft work account email");

        assertThat(new Browser(gw, mHost).microsoftSignIn("someone@gmail.com")).endsWith("/staff/sign-in?error=wrong_domain");
        assertThat(new Browser(gw, mHost).microsoftSignIn("ngozi.adebayo@" + mDomain + ".evil.example")).endsWith("/staff/sign-in?error=wrong_domain");
        assertThat(new Browser(gw, mHost).microsoftSignIn("nobody@" + mDomain)).endsWith("/staff/sign-in?error=not_linked");

        Browser admin = new Browser(gw, mHost);
        assertThat(admin.microsoftSignIn(mAdmin)).startsWith("/auth/continue?next=");
        mAdminSecret = setUpTwoStep(admin);
        assertThat(auditSummaries(mId)).anyMatch(s -> s.startsWith("SIGN_IN |") && s.contains("(Microsoft and a new two-step code)"))
                .anyMatch(s -> s.startsWith("SIGN_IN_FAILED |") && s.contains("wrong_domain, Microsoft 365"));
    }

    @Test
    @Order(4)
    void emailCodeUniversity() {
        Browser b = new Browser(gw, eHost);
        assertThat(b.googleSignIn(eAdmin)).isEqualTo("/staff/sign-in?error=wrong_method");

        // Wrong domain is said plainly (the domain is on the page anyway).
        JsonNode wrong = ok(b.post("/api/v1/auth/email-code/start", Map.of("email", "someone@gmail.com")), 400);
        assertThat(wrong.get("error").asText()).isEqualTo("wrong_domain");
        assertThat(wrong.at("/fields/email").asText()).contains("Use your @" + eDomain + " work email");
        assertThat(ok(b.post("/api/v1/auth/email-code/start", Map.of("email", "not-an-email")), 400).at("/fields/email").asText()).contains("work email");

        // A staff member and a stranger on the domain get the same answer.
        JsonNode real = ok(b.post("/api/v1/auth/email-code/start", Map.of("email", eAdmin.toUpperCase(), "returnTo", "/staff")), 200);
        Browser stranger = new Browser(gw, eHost);
        String nobody = "nobody." + run + "@" + eDomain;
        JsonNode fake = ok(stranger.post("/api/v1/auth/email-code/start", Map.of("email", nobody, "returnTo", "/staff")), 200);
        assertThat(fieldNames(real)).isEqualTo(fieldNames(fake));
        for (String f : List.of("codeSent", "expiresInSeconds", "resendInSeconds", "sendsLeftThisHour")) {
            assertThat(fake.get(f).asLong()).as(f).isCloseTo(real.get(f).asLong(), org.assertj.core.data.Offset.offset(2L));
        }
        assertThat(real.get("email").asText()).isEqualTo(eAdmin);
        assertThat(emailsTo(nobody)).isEmpty();
        String realFlow = real.get("flowId").asText(), fakeFlow = fake.get("flowId").asText();

        // Pressing "Send a new code" straight away: the same wait for both.
        JsonNode soonReal = ok(b.post("/api/v1/auth/email-code/resend", Map.of("flowId", realFlow)), 429);
        JsonNode soonFake = ok(stranger.post("/api/v1/auth/email-code/resend", Map.of("flowId", fakeFlow)), 429);
        assertThat(soonReal.get("error").asText()).isEqualTo("resend_too_soon").isEqualTo(soonFake.get("error").asText());

        // Three wrong codes: two "not right", then a new code, for the staff member and the stranger alike.
        String first = latestCode(eAdmin);
        String wrongCode = first.equals("000000") ? "111111" : "000000";
        for (Browser who : List.of(b, stranger)) {
            String flow = who == b ? realFlow : fakeFlow;
            JsonNode w1 = ok(who.post("/api/v1/auth/email-code/verify", Map.of("flowId", flow, "code", wrongCode)), 400);
            assertThat(w1.get("error").asText()).isEqualTo("wrong_code");
            assertThat(w1.get("attemptsLeft").asInt()).isEqualTo(2);
            assertThat(ok(who.post("/api/v1/auth/email-code/verify", Map.of("flowId", flow, "code", wrongCode)), 400).get("attemptsLeft").asInt()).isEqualTo(1);
            JsonNode third = ok(who.post("/api/v1/auth/email-code/verify", Map.of("flowId", flow, "code", wrongCode)), 400);
            assertThat(third.get("error").asText()).isEqualTo("new_code_sent");
            assertThat(third.get("message").asText()).contains("sent a new one");
        }
        String second = latestCode(eAdmin);
        // The first code is used up.
        if (!second.equals(first)) {
            assertThat(ok(b.post("/api/v1/auth/email-code/verify", Map.of("flowId", realFlow, "code", first)), 400).get("error").asText()).isEqualTo("wrong_code");
        }

        // The right (new) code is the first step; then the authenticator two-step as usual.
        JsonNode next = ok(b.post("/api/v1/auth/email-code/verify", Map.of("flowId", realFlow, "code", second)), 200);
        assertThat(next.get("next").asText()).isEqualTo("/auth/continue?next=%2Fstaff");
        setUpTwoStep(b);
        assertThat(b.get("/api/v1/session").json().at("/user/email").asText()).isEqualTo(eAdmin);
        // The flow ended: the same code does not work twice.
        assertThat(ok(new Browser(gw, eHost).post("/api/v1/auth/email-code/verify", Map.of("flowId", realFlow, "code", second)), 410).get("error").asText())
                .isEqualTo("expired");

        // She invites Mrs Ade: the invitation says how this university signs in.
        JsonNode ade = ok(b.post("/api/v1/people", Map.of("fullName", "Mrs Bisi Ade", "email", eStaff,
                "roles", List.of(Map.of("role", "EXAM_OFFICER", "scopeType", "UNIVERSITY")))), 201);
        assertThat(emailsTo(eStaff).get(0).get("body").asText()).contains("Sign in with a code sent to your work email");
        JsonNode outside = ok(b.post("/api/v1/people", Map.of("fullName", "Someone", "email", "someone@gmail.com",
                "roles", List.of(Map.of("role", "EXAM_OFFICER", "scopeType", "UNIVERSITY")))), 400);
        assertThat(outside.at("/fields/email").asText()).contains("@" + eDomain + " work email");

        // An expired code.
        Browser adeB = new Browser(gw, eHost);
        String adeFlow = ok(adeB.post("/api/v1/auth/email-code/start", Map.of("email", eStaff)), 200).get("flowId").asText();
        String adeCode = latestCode(eStaff);
        assertThat(ok(chuka.post("/api/v1/dev/staff-codes/expire", Map.of("tenantId", eId, "email", eStaff)), 200).get("expired").asBoolean()).isTrue();
        JsonNode expired = ok(adeB.post("/api/v1/auth/email-code/verify", Map.of("flowId", adeFlow, "code", adeCode)), 410);
        assertThat(expired.get("error").asText()).isEqualTo("code_expired");
        assertThat(expired.get("message").asText()).contains("Send a new one");

        // Suspended: the same answer on the page; the mailbox is told why, and no code is sent.
        ok(b.post("/api/v1/people/" + ade.get("id").asText() + "/suspend", Map.of("reason", "Left the university")), 200);
        int codesBefore = (int) emailsTo(eStaff).stream().filter(x -> x.get("subject").asText().contains("sign-in code")).count();
        JsonNode suspended = ok(new Browser(gw, eHost).post("/api/v1/auth/email-code/start", Map.of("email", eStaff)), 200);
        assertThat(fieldNames(suspended)).isEqualTo(fieldNames(real));
        List<JsonNode> adeMail = emailsTo(eStaff);
        assertThat(adeMail.get(0).get("subject").asText()).contains("cannot sign in");
        assertThat(adeMail.get(0).get("body").asText()).contains("your staff account is suspended");
        assertThat(adeMail.stream().filter(x -> x.get("subject").asText().contains("sign-in code")).count()).isEqualTo(codesBefore);

        assertThat(auditSummaries(eId))
                .anyMatch(s -> s.startsWith("SIGN_IN |") && s.contains("(an emailed code and a new two-step code)"))
                .anyMatch(s -> s.startsWith("SIGN_IN_CODE_SENT |"))
                .anyMatch(s -> s.startsWith("SIGN_IN_FAILED |") && s.contains(nobody) && s.contains("not_linked"))
                .anyMatch(s -> s.startsWith("SIGN_IN_FAILED |") && s.contains("wrong_domain, Email code"))
                .anyMatch(s -> s.startsWith("SIGN_IN_FAILED |") && s.contains(eStaff) && s.contains("suspended"));
    }

    @Test
    @Order(5)
    void providerSwitchesAUniversityToEmailCodesAndStaffKeepTheirAccounts() {
        JsonNode badPatch = ok(chuka.send("PATCH", "/api/v1/platform/tenants/" + mId, Map.of("staffSignIn", "FACEBOOK"), "application/json"), 400);
        assertThat(badPatch.at("/fields/staffSignIn").asText()).isNotBlank();
        JsonNode changed = ok(chuka.send("PATCH", "/api/v1/platform/tenants/" + mId, Map.of("staffSignIn", "EMAIL_CODE"), "application/json"), 200);
        assertThat(changed.get("staffSignIn").asText()).isEqualTo("EMAIL_CODE");
        assertThat(auditSummaries(mId)).anyMatch(s -> s.startsWith("STAFF_SIGN_IN_CHANGED |") && s.contains("from Microsoft 365 to Email code"));
        assertThat(new Browser(gw, mHost).get("/api/v1/public/context").json().at("/tenant/staffSignIn").asText()).isEqualTo("EMAIL_CODE");

        Browser b = new Browser(gw, mHost);
        assertThat(b.microsoftSignIn(mAdmin)).isEqualTo("/staff/sign-in?error=wrong_method");
        String flow = ok(b.post("/api/v1/auth/email-code/start", Map.of("email", mAdmin)), 200).get("flowId").asText();
        ok(b.post("/api/v1/auth/email-code/verify", Map.of("flowId", flow, "code", latestCode(mAdmin))), 200);
        // Same account: her authenticator is already set up, so she is asked for its code (not a new set-up).
        assertThat(b.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_REQUIRED");
        assertThat(ok(b.post("/api/v1/auth/mfa/verify", Map.of("code", code(mAdminSecret), "trustDevice", false)), 200).get("stage").asText())
                .isEqualTo("SIGNED_IN");
        assertThat(auditSummaries(mId)).anyMatch(s -> s.startsWith("SIGN_IN |") && s.contains("(an emailed code and a two-step code)"));

        // And back to Microsoft: the old method works again.
        ok(chuka.send("PATCH", "/api/v1/platform/tenants/" + mId, Map.of("staffSignIn", "MICROSOFT"), "application/json"), 200);
        Browser again = new Browser(gw, mHost);
        assertThat(again.microsoftSignIn(mAdmin)).startsWith("/auth/continue?next=");
        assertThat(again.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_REQUIRED");
    }

    static Set<String> fieldNames(JsonNode n) {
        Set<String> out = new TreeSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
