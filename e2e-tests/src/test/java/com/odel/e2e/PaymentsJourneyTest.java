package com.odel.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 payments end to end, through the api-gateway of a running stack with the dev profile and the FAKE gateway
 * (ODEL_PAYSTACK_MODE=fake): the provider sets the university's settlement account; Adeola sees "Not paid", her card is declined,
 * she closes the checkout, then pays (the signed webhook arrives before she is back) and gets her receipt; the same webhook again
 * changes nothing; for the 2nd semester the webhook comes after she is back. Chidi pays by transfer, confirmed 20 minutes later, and by
 * USSD with a lost webhook caught by the 30-minute re-check. Bayo pays twice and is refunded automatically, then his bank reverses
 * the payment and registration locks again. The office sees A-01 without the split; the provider sees the split. Tenant isolation.
 *
 * Run: ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test -Dtest=PaymentsJourneyTest
 * If the provider account already has two-step set up, ODEL_E2E_PROVIDER_TOTP_SECRET must hold its secret (as for B0).
 */
@EnabledIfEnvironmentVariable(named = "ODEL_E2E_GATEWAY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentsJourneyTest {
    final String gw = System.getenv("ODEL_E2E_GATEWAY");
    final String base = Optional.ofNullable(System.getenv("ODEL_E2E_BASE_DOMAIN")).orElse("localhost:3000");
    final String console = Optional.ofNullable(System.getenv("ODEL_E2E_CONSOLE_HOST")).orElse("console." + base);
    final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36) + Integer.toString(new Random().nextInt(1296), 36);
    final String wesleySlug = "wes-pay-" + run, crestSlug = "crest-pay-" + run;
    final String wesleyHost = wesleySlug + "." + base, crestHost = crestSlug + "." + base;
    final String wesleyDomain = wesleySlug + ".edu.ng", crestDomain = crestSlug + ".edu.ng";
    final String ngozi = "ngozi.adebayo@" + wesleyDomain, yemi = "yemi.adeoye@" + crestDomain;
    final String studentMail = "pay-" + run + ".test";
    final String adeolaEmail = "adeola.okafor@" + studentMail, chidiEmail = "chidi.nwosu@" + studentMail, bayoEmail = "bayo.adewale@" + studentMail;
    final String pw = "Adeola2026";
    final Set<String> usedCodes = new HashSet<>();
    final HttpClient raw = HttpClient.newHttpClient();

    Browser chuka, ngoziB, yemiB, adeola, chidi, bayo;
    String wesleyId, crestId, sem1, sem2, adeolaStudentId, kunleStudentId, chidiStudentId, bayoStudentId;
    String adeolaPaid1, adeolaPaidRef1, chidiTransferRef, bayoCardId, bayoCardRef, bayoTransferRef;
    final List<String> wesleyRefs = new ArrayList<>();
    JsonNode firstWebhook;

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
        u.put("name", name + " (" + slug + ")");   // names are unique across the platform
        u.put("shortName", shortName);
        u.put("slug", slug);
        u.put("googleDomain", domain);
        u.put("firstAdmin", Map.of("fullName", admin, "email", adminEmail));
        u.put("branding", Map.of("primaryColor", primary, "accentColor", "#C27808"));
        Browser.Res created = chuka.post("/api/v1/platform/tenants", u);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.json().get("id").asText();
    }

    static JsonNode ok(Browser.Res r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.json();
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

    int emailCount(String to, String subjectPart) {
        int n = 0;
        for (JsonNode e : chuka.get("/api/v1/dev/emails?to=" + URLEncoder.encode(to, StandardCharsets.UTF_8)).json()) {
            if (e.get("subject").asText().contains(subjectPart)) n++;
        }
        return n;
    }

    String codeFrom(String to) throws InterruptedException {
        JsonNode e = email(to, "activation code", 60);
        assertThat(e).as("activation code email to " + to).isNotNull();
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(e.get("body").asText());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    String addStudent(String matric, String surname, String first, String email) {
        Map<String, Object> add = new LinkedHashMap<>(Map.of("surname", surname, "firstName", first, "matricNumber", matric,
                "programmeCode", "BSC-CSC-ODL", "level", 200, "centreCode", "AKR", "email", email, "sendWelcome", false));
        return ok(ngoziB.post("/api/v1/students", add), 201).get("id").asText();
    }

    Browser activate(String matric, String email) throws InterruptedException {
        Browser b = new Browser(gw, wesleyHost);
        b.get("/api/v1/public/context");
        String id = ok(b.post("/api/v1/auth/student/activation/start", Map.of("login", matric)), 200).get("activationId").asText();
        String token = ok(b.post("/api/v1/auth/student/activation/verify", Map.of("activationId", id, "code", codeFrom(email))), 200)
                .get("activationToken").asText();
        assertThat(b.post("/api/v1/auth/student/activation/password", Map.of("activationToken", token, "password", pw, "confirmPassword", pw)).status()).isEqualTo(204);
        ok(b.post("/api/v1/auth/student/activation/complete", Map.of("activationToken", token, "acceptPrivacyNotice", true, "privacyNoticeVersion", "2026-11")), 200);
        return b;
    }

    JsonNode fee(Browser b) {
        return ok(b.get("/api/v1/payments/fees/current"), 200);
    }

    JsonNode feeFor(Browser b, String semesterId) {
        for (JsonNode f : ok(b.get("/api/v1/payments/fees"), 200).get("items")) if (f.get("semesterId").asText().equals(semesterId)) return f;
        throw new AssertionError("no fee for " + semesterId);
    }

    JsonNode simulate(String reference, String outcome, String channel, boolean webhook) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("outcome", outcome, "webhook", webhook));
        if (channel != null) body.put("channel", channel);
        return ok(chuka.post("/api/v1/dev/payments/" + URLEncoder.encode(reference, StandardCharsets.UTF_8) + "/simulate", body), 200);
    }

    /** Posts a webhook through the gateway exactly as Paystack does: no session, no CSRF, the signature header. */
    int postWebhook(String body, String signature) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(gw + "/api/v1/payments/webhooks/paystack"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) r.header("x-paystack-signature", signature);
        return raw.send(r.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    JsonNode start(Browser b, Map<String, Object> body, int status) {
        JsonNode p = ok(b.post("/api/v1/payments", body), status);
        if (p.hasNonNull("reference")) wesleyRefs.add(p.get("reference").asText());
        return p;
    }

    /** The fake checkout page and one of its buttons; returns the reference the browser comes back with. */
    String checkout(Browser b, JsonNode payment, String outcome) {
        String url = payment.at("/checkout/authorizationUrl").asText();
        assertThat(url).startsWith("/api/v1/dev/payments/checkout/");
        Browser.Res page = b.get(url);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("₦15,000").contains(payment.get("reference").asText());
        Browser.Res back = b.get(url + "/" + outcome);
        assertThat(back.status()).isEqualTo(302);
        assertThat(back.location()).contains(wesleyHost + "/payments/return").contains("reference=" + payment.get("reference").asText());
        return payment.get("reference").asText();
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
        assertThat(chuka.get("/api/v1/platform/payments/universities").status()).as("payment-service must be running").isEqualTo(200);
        wesleyId = createUniversity("Wesley University", "Wesley ODeL", wesleySlug, wesleyDomain, "Ngozi Adebayo", ngozi, "#1A2368");
        crestId = createUniversity("Crestfield University", "Crestfield ODL", crestSlug, crestDomain, "Yemi Adeoye", yemi, "#0F6E6E");
        ngoziB = new Browser(gw, wesleyHost);
        firstSignIn(ngoziB, ngozi);
        yemiB = new Browser(gw, crestHost);
        firstSignIn(yemiB, yemi);

        // W-10 in small: the 2026/2027 session with two semesters, a programme and the Akure centre.
        JsonNode session = ok(ngoziB.post("/api/v1/academic/sessions", AcademicSetupJourneyTest.session("2026/2027", true)), 201);
        assertThat(session.get("status").asText()).isEqualTo("ACTIVE");
        for (JsonNode s : session.get("semesters")) {
            if (s.get("number").asInt() == 1) sem1 = s.get("id").asText(); else sem2 = s.get("id").asText();
        }
        String faculty = ok(ngoziB.post("/api/v1/academic/faculties", Map.of("name", "Faculty of Science", "code", "SCI")), 201).get("id").asText();
        String dept = ok(ngoziB.post("/api/v1/academic/departments", Map.of("facultyId", faculty, "name", "Computer Science", "code", "CSC")), 201).get("id").asText();
        ok(ngoziB.post("/api/v1/academic/programmes", Map.of("departmentId", dept, "name", "B.Sc. Computer Science", "code", "BSC-CSC-ODL",
                "degree", "B.Sc.", "durationYears", 4, "entryLevels", List.of(100, 200, 300, 400))), 201);
        ok(ngoziB.post("/api/v1/academic/centres", Map.of("name", "Akure Study Centre", "code", "AKR", "address", "1 Campus Road", "state", "Ondo",
                "phone", "0803 555 0117")), 201);

        adeolaStudentId = addStudent("WU/ODL/24/0153", "Okafor", "Adeola", adeolaEmail);
        chidiStudentId = addStudent("WU/ODL/24/0154", "Nwosu", "Chidi", chidiEmail);
        bayoStudentId = addStudent("WU/ODL/24/0688", "Adewale", "Bayo", bayoEmail);
        kunleStudentId = addStudent("WU/ODL/24/0172", "Bamidele", "Kunle", "kunle.bamidele@" + studentMail);
        adeola = activate("WU/ODL/24/0153", adeolaEmail);
        chidi = activate("WU/ODL/24/0154", chidiEmail);
        bayo = activate("WU/ODL/24/0688", bayoEmail);
    }

    // ------------------------------------------------------------------ the provider sets the university up

    @Test
    @Order(1)
    void providerSetsTheSettlementAccount() {
        // P-01 before anything: Not paid, locked. Payments are not open until the provider has set the account.
        JsonNode f = fee(adeola);
        assertThat(f.get("label").asText()).isEqualTo("2026/2027 · 1st semester");
        assertThat(f.get("amountLabel").asText()).isEqualTo("₦15,000");
        assertThat(f.get("status").asText()).isEqualTo("NOT_PAID");
        assertThat(f.get("statusLabel").asText()).isEqualTo("Not paid");
        assertThat(f.get("locked").asBoolean()).isTrue();
        assertThat(f.get("actions").toString()).contains("PAY");
        assertThat(f.get("ussdBanks").findValuesAsText("name")).containsExactly("GTBank", "UBA", "Sterling", "Zenith");
        assertThat(ok(adeola.post("/api/v1/payments", Map.of("method", "CARD")), 409).get("error").asText()).isEqualTo("payments_not_ready");
        assertThat(ok(ngoziB.get("/api/v1/fees/settings"), 200).get("paymentsOpen").asBoolean()).isFalse();

        JsonNode uni = ok(chuka.get("/api/v1/platform/payments/universities/" + wesleyId), 200);
        assertThat(uni.get("amount").asInt()).isEqualTo(15000);
        assertThat(uni.get("centreShare").asInt()).isEqualTo(3000);
        assertThat(uni.get("providerShare").asInt()).isEqualTo(12000);
        assertThat(uni.get("paymentsOpen").asBoolean()).isFalse();
        assertThat(ok(chuka.send("PUT", "/api/v1/platform/payments/universities/" + wesleyId, Map.of("amount", 500, "centreShare", 3000), "application/json"), 400)
                .at("/fields/amount").asText()).contains("₦1,000");

        JsonNode banks = ok(chuka.get("/api/v1/platform/payments/banks"), 200);
        assertThat(banks.get("items").findValuesAsText("code")).contains("058");
        assertThat(ok(chuka.post("/api/v1/platform/payments/banks/resolve", Map.of("bankCode", "058", "accountNumber", "12345")), 400)
                .at("/fields/accountNumber").asText()).isEqualTo("Enter the 10-digit account number.");
        assertThat(ok(chuka.post("/api/v1/platform/payments/banks/resolve", Map.of("bankCode", "058", "accountNumber", "1111111111")), 422)
                .get("error").asText()).isEqualTo("not_resolved");
        assertThat(ok(chuka.send("PUT", "/api/v1/platform/payments/universities/" + wesleyId + "/settlement-account",
                Map.of("resolutionId", UUID.randomUUID().toString(), "confirmedAccountName", "X"), "application/json"), 409).get("error").asText()).isEqualTo("resolve_first");
        JsonNode resolved = ok(chuka.post("/api/v1/platform/payments/banks/resolve", Map.of("bankCode", "058", "accountNumber", "0123454521")), 200);
        assertThat(resolved.get("accountNumberMasked").asText()).isEqualTo("••••4521");
        assertThat(resolved.toString()).doesNotContain("0123454521");
        String name = resolved.get("accountName").asText();
        assertThat(ok(chuka.send("PUT", "/api/v1/platform/payments/universities/" + wesleyId + "/settlement-account",
                Map.of("resolutionId", resolved.get("resolutionId").asText(), "confirmedAccountName", "Someone else"), "application/json"), 400)
                .at("/fields/confirmedAccountName").asText()).isNotBlank();
        JsonNode saved = ok(chuka.send("PUT", "/api/v1/platform/payments/universities/" + wesleyId + "/settlement-account",
                Map.of("resolutionId", resolved.get("resolutionId").asText(), "confirmedAccountName", name), "application/json"), 200);
        assertThat(saved.get("paymentsOpen").asBoolean()).isTrue();
        assertThat(saved.at("/settlementAccount/subaccountCode").asText()).startsWith("ACCT_");
        assertThat(saved.at("/settlementAccount/bankName").asText()).isEqualTo("Guaranty Trust Bank");
        assertThat(saved.at("/settlementAccount/updatedBy/fullName").asText()).isEqualTo("Chuka Obi");

        // The university only views it: no code, no split, number masked; it has no way to change it.
        JsonNode mine = ok(ngoziB.get("/api/v1/fees/settings"), 200);
        assertThat(mine.get("paymentsOpen").asBoolean()).isTrue();
        assertThat(mine.at("/settlementAccount/accountNumberMasked").asText()).isEqualTo("••••4521");
        assertThat(mine.at("/settlementAccount/accountName").asText()).isEqualTo(name);
        assertThat(mine.toString()).doesNotContain("ACCT_").doesNotContain("centreShare").doesNotContain("providerShare");
        assertThat(ngoziB.send("PUT", "/api/v1/platform/payments/universities/" + wesleyId + "/settlement-account", Map.of(), "application/json").status()).isEqualTo(403);
        assertThat(ngoziB.get("/api/v1/platform/payments/universities").status()).isEqualTo(403);

        // Audit: who and when, bank and last four digits only.
        JsonNode audit = chuka.get("/api/v1/platform/audit?size=200&tenantId=" + wesleyId).json();
        assertThat(audit.toString()).contains("PAYMENT_ACCOUNT_SET").contains("••••4521").doesNotContain("0123454521");
    }

    // ------------------------------------------------------------------ Adeola, 1st semester

    @Test
    @Order(2)
    void adeolaCardDeclinedThenClosedThenPaid() throws Exception {
        // P-07 card declined.
        JsonNode p = start(adeola, Map.of("method", "CARD"), 201);
        assertThat(p.get("status").asText()).isEqualTo("PENDING");
        assertThat(p.get("label").asText()).isEqualTo("2026/2027 · 1st semester");
        assertThat(p.get("reference").asText()).matches("WU-PF-\\d{6}");
        String ref = checkout(adeola, p, "decline");
        JsonNode back = ok(adeola.get("/api/v1/payments/return?reference=" + ref), 200);
        assertThat(back.get("status").asText()).isEqualTo("FAILED");
        assertThat(back.get("failureReason").asText()).contains("declined");
        assertThat(fee(adeola).get("status").asText()).isEqualTo("NOT_PAID");

        // P-08 checkout closed: nothing charged, the payment stays open; starting again returns the same one.
        JsonNode p2 = start(adeola, Map.of("method", "CARD"), 201);
        checkout(adeola, p2, "close");
        JsonNode closed = ok(adeola.get("/api/v1/payments/return?reference=" + p2.get("reference").asText()), 200);
        assertThat(closed.get("status").asText()).isEqualTo("PENDING");
        assertThat(closed.get("message").asText()).contains("closed the payment page");
        JsonNode same = start(adeola, Map.of("method", "CARD"), 200);
        assertThat(same.get("id").asText()).as("one open payment per student per semester").isEqualTo(p2.get("id").asText());
        assertThat(fee(adeola).at("/openPayment/id").asText()).isEqualTo(p2.get("id").asText());

        // USSD with a bank Paystack's Charge API does not support.
        assertThat(ok(adeola.post("/api/v1/payments", Map.of("method", "USSD", "ussdBank", "ACCESS")), 400).get("error").asText())
                .isEqualTo("ussd_bank_not_supported");

        // P-02 → P-03 → P-06: she pays; the signed webhook arrives before her browser is back.
        String ref2 = checkout(adeola, same, "success");
        JsonNode paid = ok(adeola.get("/api/v1/payments/return?reference=" + ref2), 200);
        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("methodLabel").asText()).isEqualTo("Debit card · GTBank card ending 4412");
        adeolaPaid1 = paid.get("id").asText();
        adeolaPaidRef1 = ref2;

        // P-01b: paid, unlocked, receipt.
        JsonNode f = fee(adeola);
        assertThat(f.get("status").asText()).isEqualTo("PAID");
        assertThat(f.get("locked").asBoolean()).isFalse();
        assertThat(f.get("message").asText()).startsWith("Paid on ");
        assertThat(f.at("/paidPayment/reference").asText()).isEqualTo(ref2);
        assertThat(f.get("actions").toString()).contains("DOWNLOAD_RECEIPT").doesNotContain("\"PAY\"");
        assertThat(ok(adeola.post("/api/v1/payments", Map.of("method", "CARD")), 409).get("error").asText()).isEqualTo("already_paid");

        // P-10: the receipt PDF; and the email with it attached.
        Browser.Res pdf = adeola.get(f.at("/paidPayment/receiptUrl").asText());
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.body()).startsWith("%PDF-");
        JsonNode mail = email(adeolaEmail, "Receipt: platform fee 2026/2027 · 1st semester", 30);
        assertThat(mail).as("receipt email").isNotNull();
        assertThat(mail.get("attachments").asText()).isEqualTo("receipt-" + ref2 + ".pdf");
        assertThat(mail.get("body").asText()).contains(ref2).contains("₦15,000").contains("Debit card · GTBank card ending 4412");
        assertThat(adeola.get("/api/v1/payments/" + p.get("id").asText() + "/receipt.pdf").status()).as("no receipt for a declined card").isEqualTo(404);

        // P-11: every attempt.
        JsonNode mine = ok(adeola.get("/api/v1/payments/mine"), 200);
        assertThat(mine.get("items").findValuesAsText("status")).containsExactlyInAnyOrder("PAID", "FAILED");
    }

    @Test
    @Order(3)
    void theSameWebhookTwiceChangesNothing() throws Exception {
        // The fake gives the exact webhook it sent for her payment; post it again (twice) through the gateway.
        JsonNode sim = simulate(adeolaPaidRef1, "SUCCESS", "card", false);
        firstWebhook = sim.at("/webhooks/0");
        assertThat(firstWebhook.get("event").asText()).isEqualTo("charge.success");
        int before = emailCount(adeolaEmail, "Receipt: platform fee");
        assertThat(postWebhook(firstWebhook.get("body").asText(), firstWebhook.get("signature").asText())).isEqualTo(200);
        assertThat(postWebhook(firstWebhook.get("body").asText(), firstWebhook.get("signature").asText())).isEqualTo(200);
        Thread.sleep(1500);
        assertThat(emailCount(adeolaEmail, "Receipt: platform fee")).as("no second receipt").isEqualTo(before);
        assertThat(fee(adeola).get("status").asText()).isEqualTo("PAID");
        assertThat(ok(adeola.get("/api/v1/payments/mine"), 200).get("items").size()).isEqualTo(2);

        // Nothing passes without the right signature.
        assertThat(postWebhook(firstWebhook.get("body").asText(), null)).isEqualTo(401);
        assertThat(postWebhook(firstWebhook.get("body").asText(), "00" + firstWebhook.get("signature").asText().substring(2))).isEqualTo(401);
        assertThat(postWebhook(firstWebhook.get("body").asText().replace("1500000", "150000"), firstWebhook.get("signature").asText())).isEqualTo(401);
    }

    @Test
    @Order(4)
    void secondSemesterWebhookAfterTheBrowserReturns() throws Exception {
        JsonNode f2 = feeFor(adeola, sem2);
        assertThat(f2.get("label").asText()).isEqualTo("2026/2027 · 2nd semester");
        assertThat(f2.get("status").asText()).isEqualTo("NOT_PAID");
        JsonNode p = start(adeola, Map.of("method", "CARD", "semesterId", sem2), 201);
        JsonNode sim = simulate(p.get("reference").asText(), "SUCCESS", "card", false);   // paid, webhook held back
        JsonNode back = ok(adeola.get("/api/v1/payments/return?reference=" + p.get("reference").asText()), 200);
        assertThat(back.get("status").asText()).as("the browser never marks paid").isEqualTo("CONFIRMING");
        assertThat(back.get("message").asText()).contains("Do not pay again");
        assertThat(feeFor(adeola, sem2).get("status").asText()).isEqualTo("CONFIRMING");
        assertThat(ok(adeola.post("/api/v1/payments", Map.of("method", "BANK_TRANSFER", "semesterId", sem2)), 409).get("error").asText())
                .isEqualTo("payment_confirming");
        JsonNode hook = sim.at("/webhooks/0");
        assertThat(postWebhook(hook.get("body").asText(), hook.get("signature").asText())).isEqualTo(200);
        assertThat(feeFor(adeola, sem2).get("status").asText()).isEqualTo("PAID");
        assertThat(fee(adeola).get("label").asText()).as("Home still shows the current semester").isEqualTo("2026/2027 · 1st semester");
    }

    // ------------------------------------------------------------------ Chidi: transfer later, USSD with a lost webhook

    @Test
    @Order(5)
    void chidiTransferConfirmedTwentyMinutesLater() {
        JsonNode p = start(chidi, Map.of("method", "BANK_TRANSFER"), 201);
        chidiTransferRef = p.get("reference").asText();
        assertThat(p.at("/transfer/bankName").asText()).isEqualTo("Paystack-Titan");
        assertThat(p.at("/transfer/accountNumber").asText()).matches("\\d{10}");
        assertThat(p.at("/transfer/amountLabel").asText()).isEqualTo("₦15,000.00");
        Instant expires = Instant.parse(p.at("/transfer/expiresAt").asText());
        assertThat(expires).isBetween(Instant.now().plusSeconds(28 * 60), Instant.now().plusSeconds(31 * 60));

        // P-04 "I have made the transfer" → P-05 / P-01e: confirming, no Pay button.
        JsonNode sent = ok(chidi.post("/api/v1/payments/" + p.get("id").asText() + "/sent", Map.of()), 200);
        assertThat(sent.get("status").asText()).isEqualTo("CONFIRMING");
        JsonNode home = fee(chidi);
        assertThat(home.get("status").asText()).isEqualTo("CONFIRMING");
        assertThat(home.get("message").asText()).isEqualTo("We are waiting for your bank to confirm your payment. Do not pay again.");
        assertThat(home.get("actions").toString()).contains("CHECK_STATUS").doesNotContain("\"PAY\"");
        assertThat(ok(chidi.post("/api/v1/payments", Map.of("method", "CARD")), 409).get("error").asText())
                .isEqualTo("payment_confirming");

        // 20 minutes on: the re-check leaves it alone (it asks only after 30 minutes); then the bank confirms by webhook.
        ok(chuka.post("/api/v1/dev/payments/recheck", Map.of("minutesAhead", 20)), 200);
        assertThat(fee(chidi).get("status").asText()).isEqualTo("CONFIRMING");
        simulate(chidiTransferRef, "SUCCESS", "bank_transfer", true);
        JsonNode f = fee(chidi);
        assertThat(f.get("status").asText()).isEqualTo("PAID");
        assertThat(f.at("/paidPayment/methodLabel").asText()).startsWith("Bank transfer");
    }

    @Test
    @Order(6)
    void chidiUssdWithALostWebhookIsCaughtByTheRecheck() {
        JsonNode p = start(chidi, Map.of("method", "USSD", "ussdBank", "GTBANK", "semesterId", sem2), 201);
        assertThat(p.at("/ussd/bank").asText()).isEqualTo("GTBank");
        assertThat(p.at("/ussd/code").asText()).startsWith("*737*").endsWith("#");
        ok(chidi.post("/api/v1/payments/" + p.get("id").asText() + "/sent", Map.of()), 200);
        simulate(p.get("reference").asText(), "SUCCESS", "ussd", false);   // the bank took the money; the webhook is lost
        assertThat(feeFor(chidi, sem2).get("status").asText()).isEqualTo("CONFIRMING");
        JsonNode r = ok(chuka.post("/api/v1/dev/payments/recheck", Map.of("minutesAhead", 31)), 200);
        assertThat(r.get("paid").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode f = feeFor(chidi, sem2);
        assertThat(f.get("status").asText()).isEqualTo("PAID");
        assertThat(f.at("/paidPayment/methodLabel").asText()).isEqualTo("USSD (GTBank)");
    }

    // ------------------------------------------------------------------ Bayo: paid twice, refunded; then reversed

    @Test
    @Order(7)
    void bayoPaysTwiceAndIsRefundedAutomatically() throws Exception {
        // A transfer he thinks failed; he switches to card ("Pay another way"), pays; then the transfer arrives too.
        JsonNode t = start(bayo, Map.of("method", "BANK_TRANSFER"), 201);
        bayoTransferRef = t.get("reference").asText();
        JsonNode c = start(bayo, Map.of("method", "CARD"), 201);
        assertThat(ok(bayo.get("/api/v1/payments/" + t.get("id").asText()), 200).get("status").asText()).isEqualTo("CANCELLED");
        bayoCardId = c.get("id").asText();
        bayoCardRef = checkout(bayo, c, "success");
        assertThat(fee(bayo).get("status").asText()).isEqualTo("PAID");

        simulate(bayoTransferRef, "SUCCESS", "bank_transfer", true);
        JsonNode f = fee(bayo);
        assertThat(f.get("status").asText()).as("still paid").isEqualTo("PAID");
        assertThat(f.at("/duplicate/reference").asText()).isEqualTo(bayoTransferRef);
        assertThat(f.at("/duplicate/refund/reference").asText()).matches("RF-\\d{5}");
        assertThat(f.at("/duplicate/refund/status").asText()).isEqualTo("PENDING");
        assertThat(f.at("/duplicate/refund/expectedBy").asText()).isNotBlank();
        JsonNode dup = ok(bayo.get("/api/v1/payments/" + t.get("id").asText()), 200);
        assertThat(dup.get("status").asText()).isEqualTo("REFUNDING");
        assertThat(dup.has("receiptUrl")).as("no receipt for a refunded duplicate").isFalse();
        assertThat(email(bayoEmail, "You paid twice", 30)).as("E-07 email").isNotNull();

        // E-07b: the refund reaches him.
        simulate(bayoTransferRef, "REFUND_PROCESSED", null, true);
        assertThat(ok(bayo.get("/api/v1/payments/" + t.get("id").asText()), 200).get("status").asText()).isEqualTo("REFUNDED");
        assertThat(fee(bayo).at("/duplicate/refund/status").asText()).isEqualTo("PROCESSED");
        assertThat(fee(bayo).get("status").asText()).isEqualTo("PAID");
        assertThat(email(bayoEmail, "Your refund of ₦15,000 has been sent", 30)).isNotNull();
    }

    @Test
    @Order(8)
    void aReversalRelocks() throws Exception {
        simulate(bayoCardRef, "REVERSE", null, true);
        JsonNode f = fee(bayo);
        assertThat(f.get("status").asText()).isEqualTo("REVERSED");
        assertThat(f.get("locked").asBoolean()).isTrue();
        assertThat(f.get("message").asText()).contains("Your bank took back the ₦15,000").contains("locked");
        assertThat(f.at("/reversal/reference").asText()).isEqualTo(bayoCardRef);
        assertThat(f.get("actions").toString()).contains("PAY");
        assertThat(email(bayoEmail, "Your bank took back your platform fee", 30)).isNotNull();
        // He can pay again.
        JsonNode again = start(bayo, Map.of("method", "CARD"), 201);
        assertThat(again.get("status").asText()).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ the office (A-01, A-02) and the provider (settlement)

    @Test
    @Order(9)
    void theOfficeSeesPaymentsButNotTheSplit() {
        JsonNode s = ok(ngoziB.get("/api/v1/fees/payments/summary"), 200);
        assertThat(s.at("/semester/label").asText()).isEqualTo("2026/2027 · 1st semester");
        assertThat(s.get("amountLabel").asText()).isEqualTo("₦15,000 per student");
        assertThat(s.get("students").asInt()).isEqualTo(4);
        assertThat(s.at("/paid/count").asInt()).isEqualTo(2);
        assertThat(s.at("/paid/collectedLabel").asText()).isEqualTo("₦30,000 collected");
        assertThat(s.at("/notPaid/count").asInt()).isEqualTo(2);
        assertThat(s.at("/refunds/count").asInt()).isEqualTo(1);
        assertThat(s.at("/reversed/count").asInt()).isEqualTo(1);

        Browser.Res listRes = ngoziB.get("/api/v1/fees/payments?size=50");
        JsonNode list = ok(listRes, 200);
        assertThat(list.get("total").asInt()).isEqualTo(4);
        assertThat(listRes.body()).doesNotContain("providerShare").doesNotContain("centreShare").doesNotContain("12000").doesNotContain("ACCT_");
        Map<String, String> byName = new HashMap<>();
        list.get("items").forEach(r -> byName.put(r.get("fullName").asText(), r.get("statusLabel").asText()));
        assertThat(byName).containsEntry("Adeola Okafor", "Paid").containsEntry("Chidi Nwosu", "Paid").containsEntry("Kunle Bamidele", "Not paid");
        assertThat(ok(ngoziB.get("/api/v1/fees/payments?status=PAID_TWICE"), 200).get("items").findValuesAsText("fullName")).containsExactly("Bayo Adewale");
        assertThat(ok(ngoziB.get("/api/v1/fees/payments?status=NOT_PAID"), 200).get("items").findValuesAsText("fullName")).contains("Kunle Bamidele");
        JsonNode byRef = ok(ngoziB.get("/api/v1/fees/payments?q=" + adeolaPaidRef1), 200);
        assertThat(byRef.get("total").asInt()).isEqualTo(1);
        assertThat(byRef.at("/items/0/methodLabel").asText()).isEqualTo("Card");
        assertThat(ok(ngoziB.get("/api/v1/fees/payments?method=BANK_TRANSFER"), 200).get("items").findValuesAsText("fullName")).contains("Chidi Nwosu");
        String today = LocalDate.now(ZoneId.of("Africa/Lagos")).toString();
        assertThat(ok(ngoziB.get("/api/v1/fees/payments?from=" + today + "&to=" + today + "&status=PAID"), 200).get("total").asInt()).isEqualTo(2);
        Browser.Res csv = ngoziB.get("/api/v1/fees/payments/export.csv");
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.body()).startsWith("student,matric_number").contains("Adeola Okafor").contains(adeolaPaidRef1);

        // A-02: one student's record, every attempt; the office may download the receipt.
        JsonNode rec = ok(ngoziB.get("/api/v1/fees/students/" + adeolaStudentId), 200);
        assertThat(rec.at("/student/matricNumber").asText()).isEqualTo("WU/ODL/24/0153");
        assertThat(rec.at("/fee/status").asText()).isEqualTo("PAID");
        assertThat(rec.get("attempts").size()).isEqualTo(2);
        assertThat(ngoziB.get("/api/v1/payments/" + adeolaPaid1 + "/receipt.pdf").body()).startsWith("%PDF-");
        assertThat(ok(ngoziB.get("/api/v1/fees/students/" + kunleStudentId), 200).at("/fee/status").asText()).isEqualTo("NOT_PAID");

        // Students never see the office's pages, and the office cannot start a student's payment.
        assertThat(adeola.get("/api/v1/fees/payments").status()).isEqualTo(403);
        assertThat(adeola.get("/api/v1/platform/payments/settlement").status()).isEqualTo(403);
        assertThat(ngoziB.post("/api/v1/payments", Map.of("method", "CARD")).status()).isEqualTo(403);
    }

    @Test
    @Order(11)
    void theProviderSeesTheSplit() {
        String today = LocalDate.now(ZoneId.of("Africa/Lagos")).toString();
        JsonNode s = ok(chuka.get("/api/v1/platform/payments/settlement?from=" + today + "&to=" + today + "&tenantId=" + wesleyId + "&size=100"), 200);
        // Adeola 1st and 2nd, Chidi transfer and USSD, Bayo card (reversed) and transfer (refunded).
        assertThat(s.at("/totals/payments").asInt()).isEqualTo(6);
        assertThat(s.at("/totals/gross").asInt()).isEqualTo(90000);
        assertThat(s.at("/totals/provider").asInt()).isEqualTo(72000);
        assertThat(s.at("/totals/centre").asInt()).isEqualTo(18000);
        assertThat(s.at("/totals/refunded").asInt()).isEqualTo(15000);
        assertThat(s.at("/totals/gatewayFees").asInt()).isEqualTo(6 * 325);
        assertThat(s.at("/totals/providerNet").asInt()).isEqualTo(72000 - 6 * 325);
        assertThat(s.get("universities").size()).isEqualTo(1);
        assertThat(s.at("/universities/0/name").asText()).startsWith("Wesley University");
        for (JsonNode i : s.get("items")) {
            assertThat(i.get("provider").asInt()).isEqualTo(12000);
            assertThat(i.get("centre").asInt()).isEqualTo(3000);
            assertThat(i.get("gatewayFee").asInt()).isEqualTo(325);
        }
        assertThat(s.get("items").findValuesAsText("reference")).contains(adeolaPaidRef1, chidiTransferRef, bayoTransferRef);
        JsonNode list = ok(chuka.get("/api/v1/platform/payments/universities"), 200);
        assertThat(list.get("items").findValuesAsText("tenantId")).contains(wesleyId, crestId);
    }

    // ------------------------------------------------------------------ what registration asks (internal, straight to the service)

    JsonNode internal(String method, String path, String body) throws Exception {
        String url = Optional.ofNullable(System.getenv("ODEL_E2E_PAYMENTS_URL")).orElse("http://127.0.0.1:9306");
        String key = Optional.ofNullable(System.getenv("ODEL_E2E_INTERNAL_KEY")).orElse("dev-internal-key");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + path)).header("X-Internal-Key", key)
                .header("X-Odel-Tenant-Id", wesleyId).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> res = raw.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.body());
    }

    @Test
    @Order(10)
    void registrationsFeeStatus() throws Exception {
        try {
            raw.send(HttpRequest.newBuilder(URI.create(Optional.ofNullable(System.getenv("ODEL_E2E_PAYMENTS_URL")).orElse("http://127.0.0.1:9306")
                    + "/actuator/health")).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            Assumptions.abort("payment-service is not reachable directly (internal calls are not routed by the gateway)");
        }
        JsonNode a = internal("GET", "/internal/payments/fee-status?studentId=" + adeolaStudentId + "&semesterId=" + sem1, null);
        assertThat(a.get("status").asText()).isEqualTo("PAID");
        assertThat(a.get("paid").asBoolean()).isTrue();
        assertThat(a.get("reference").asText()).isEqualTo(adeolaPaidRef1);
        assertThat(a.get("paidAt").asText()).isNotBlank();
        // A reversal is NOT_PAID for registration (detail in feeStatus); a refunded duplicate left Bayo's fee paid until then.
        JsonNode b = internal("GET", "/internal/payments/fee-status?studentId=" + bayoStudentId + "&semesterId=" + sem1, null);
        assertThat(b.get("status").asText()).isEqualTo("NOT_PAID");
        assertThat(b.get("feeStatus").asText()).isEqualTo("REVERSED");
        assertThat(b.get("paid").asBoolean()).isFalse();
        // No fee record: 200 NOT_PAID, never 404.
        JsonNode none = internal("GET", "/internal/payments/fee-status?studentId=" + UUID.randomUUID() + "&semesterId=" + sem1, null);
        assertThat(none.get("status").asText()).isEqualTo("NOT_PAID");
        // The current semester by default.
        assertThat(internal("GET", "/internal/payments/fee-status?studentId=" + chidiStudentId, null).get("status").asText()).isEqualTo("PAID");
        JsonNode bulk = internal("POST", "/internal/payments/fee-status/bulk", "{\"semesterId\":\"" + sem1 + "\",\"studentIds\":[\""
                + adeolaStudentId + "\",\"" + chidiStudentId + "\",\"" + bayoStudentId + "\",\"" + kunleStudentId + "\"]}");
        assertThat(bulk.get("items").findValuesAsText("status")).containsExactly("PAID", "PAID", "NOT_PAID", "NOT_PAID");
        JsonNode sum = internal("GET", "/internal/payments/summary?semesterId=" + sem1, null);
        assertThat(sum.get("paid").asInt()).isEqualTo(2);
        assertThat(sum.get("reversed").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    @Order(12)
    void anotherUniversitySeesNothing() throws Exception {
        String json = "application/json";
        // Crestfield's admin, with Wesley's ids.
        assertThat(yemiB.get("/api/v1/fees/students/" + adeolaStudentId).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/payments/" + adeolaPaid1 + "/receipt.pdf").status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/fees/payments/summary?semesterId=" + sem1).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/fees/payments?semesterId=" + sem1).status()).isEqualTo(404);
        assertThat(yemiB.get("/api/v1/fees/payments/export.csv?semesterId=" + sem1).status()).isEqualTo(404);
        Browser.Res crestList = yemiB.get("/api/v1/fees/payments");
        assertThat(crestList.status()).as("Crestfield has no session yet").isEqualTo(404);
        assertThat(crestList.json().get("error").asText()).isEqualTo("no_session");
        JsonNode crestSettings = ok(yemiB.get("/api/v1/fees/settings"), 200);
        assertThat(crestSettings.get("paymentsOpen").asBoolean()).isFalse();
        assertThat(crestSettings.toString()).doesNotContain("4521");

        // Adeola's session cookie on Crestfield's address is not a session there.
        Browser wrongHost = new Browser(gw, crestHost);
        wrongHost.cookies.putAll(adeola.cookies);
        for (String path : List.of("/api/v1/payments/fees/current", "/api/v1/payments/fees", "/api/v1/payments/mine",
                "/api/v1/payments/" + adeolaPaid1, "/api/v1/payments/" + adeolaPaid1 + "/receipt.pdf", "/api/v1/payments/return?reference=" + adeolaPaidRef1)) {
            assertThat(wrongHost.get(path).status()).as(path).isEqualTo(401);
        }
        assertThat(wrongHost.post("/api/v1/payments", Map.of("method", "CARD")).status()).isIn(401, 403);

        // Another student of the same university cannot see, check, cancel or download Adeola's payments.
        for (String path : List.of("/api/v1/payments/" + adeolaPaid1, "/api/v1/payments/" + adeolaPaid1 + "/receipt.pdf",
                "/api/v1/payments/return?reference=" + adeolaPaidRef1)) {
            assertThat(chidi.get(path).status()).as(path).isEqualTo(404);
        }
        assertThat(chidi.post("/api/v1/payments/" + adeolaPaid1 + "/check", Map.of()).status()).isEqualTo(404);
        assertThat(chidi.post("/api/v1/payments/" + adeolaPaid1 + "/sent", Map.of()).status()).isEqualTo(404);
        assertThat(chidi.post("/api/v1/payments/" + adeolaPaid1 + "/cancel", Map.of()).status()).isEqualTo(404);

        // University staff never reach the provider's endpoints.
        assertThat(yemiB.get("/api/v1/platform/payments/settlement").status()).isEqualTo(403);
        assertThat(yemiB.post("/api/v1/platform/payments/banks/resolve", Map.of("bankCode", "058", "accountNumber", "0123454521")).status()).isEqualTo(403);
        assertThat(yemiB.send("PUT", "/api/v1/platform/payments/universities/" + crestId, Map.of("amount", 1000, "centreShare", 0), json).status()).isEqualTo(403);
        assertThat(yemiB.get("/api/v1/platform/payments/universities/" + wesleyId).status()).isEqualTo(403);
    }
}
