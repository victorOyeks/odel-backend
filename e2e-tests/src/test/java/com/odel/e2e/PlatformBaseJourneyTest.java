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
 * B0 end-to-end test (presenter's guide, Build plan, B0), through the api-gateway of a running stack with the
 * dev profile: the provider creates a university, its first admin signs in with Google and two-step codes and lands
 * on an empty setup checklist in its colours; sign out and back in asks for the code; the audit log shows it all.
 * Then the edge cases and tenant isolation against a second university.
 *
 * Run: ODEL_E2E_GATEWAY=http://localhost:9300 mvn -pl e2e-tests test
 * Each run creates its own universities (random slugs), so it can run again on the same database.
 */
@EnabledIfEnvironmentVariable(named = "ODEL_E2E_GATEWAY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformBaseJourneyTest {
    final String gw = System.getenv("ODEL_E2E_GATEWAY");
    final String base = Optional.ofNullable(System.getenv("ODEL_E2E_BASE_DOMAIN")).orElse("localhost:3000");
    final String console = Optional.ofNullable(System.getenv("ODEL_E2E_CONSOLE_HOST")).orElse("console." + base);
    final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36);
    final String wesleySlug = "wesley-" + run, crestSlug = "crest-" + run;
    final String wesleyHost = wesleySlug + "." + base, crestHost = crestSlug + "." + base;
    final String wesleyDomain = "wesley-" + run + ".edu.ng", crestDomain = "crest-" + run + ".edu.ng";
    final String ngozi = "ngozi.adebayo@" + wesleyDomain;

    String chukaSecret, ngoziSecret, wesleyId;
    List<String> chukaBackupCodes = new ArrayList<>();
    Browser chuka, ngoziB;
    final Set<String> usedCodes = new HashSet<>();

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

    /** A code works only once, so each sign-in takes a different step inside the accepted window. */
    String code(String secret) {
        long now = Instant.now().getEpochSecond() / 30;
        for (long s = now - 1; s <= now + 1; s++) {
            String c = totp(secret, s);
            if (usedCodes.add(secret + c)) return c;
        }
        throw new IllegalStateException("No unused code in the window");
    }

    String firstSignIn(Browser b, String email, List<String> backupOut) {
        assertThat(b.googleSignIn(email)).startsWith("/auth/continue?next=");
        assertThat(b.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_SETUP");
        JsonNode setup = b.post("/api/v1/auth/mfa/setup", Map.of()).json();
        String secret = setup.get("secret").asText();
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + secret);
        Browser.Res confirmed = b.post("/api/v1/auth/mfa/setup/confirm", Map.of("code", code(secret)));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        confirmed.json().get("backupCodes").forEach(c -> codes.add(c.asText()));
        assertThat(codes).hasSize(8).allMatch(c -> c.matches("\\d{4}-\\d{4}"));
        if (backupOut != null) backupOut.addAll(codes);
        assertThat(confirmed.json().at("/session/stage").asText()).isEqualTo("SIGNED_IN");
        return secret;
    }

    Map<String, Object> university(String name, String shortName, String slug, String domain, String admin, String adminEmail, String primary, String accent) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", name);
        u.put("shortName", shortName);
        u.put("slug", slug);
        u.put("googleDomain", domain);
        u.put("firstAdmin", Map.of("fullName", admin, "email", adminEmail));
        u.put("branding", new LinkedHashMap<>(Map.of("primaryColor", primary, "accentColor", accent)));
        return u;
    }

    List<String> actions(String tenantId) {
        List<String> out = new ArrayList<>();
        chuka.get("/api/v1/platform/audit?size=200&tenantId=" + tenantId).json().get("items").forEach(i -> out.add(0, i.get("action").asText()));
        return out;
    }

    @Test
    @Order(1)
    void providerCreatesWesleyAndItsAdminSignsIn() throws Exception {
        chuka = new Browser(gw, console);
        assertThat(chuka.get("/api/v1/platform/tenants").status()).isEqualTo(401);
        // The provider admin may already have two-step set up from an earlier run; this run's tests need a fresh one.
        Browser.Res first = chuka.get("/api/v1/public/context");
        assertThat(first.json().get("kind").asText()).isEqualTo("PLATFORM");
        String chukaEmail = Optional.ofNullable(System.getenv("ODEL_E2E_PROVIDER_EMAIL")).orElse("chuka.obi@odel.dev");
        chuka.googleSignIn(chukaEmail);
        String stage = chuka.get("/api/v1/session").json().get("stage").asText();
        if (stage.equals("MFA_SETUP")) {
            chuka = new Browser(gw, console);
            chukaSecret = firstSignIn(chuka, chukaEmail, chukaBackupCodes);
        } else {
            chukaSecret = System.getenv("ODEL_E2E_PROVIDER_TOTP_SECRET");
            Assumptions.assumeTrue(chukaSecret != null, "provider already has two-step; set ODEL_E2E_PROVIDER_TOTP_SECRET");
            assertThat(chuka.post("/api/v1/auth/mfa/verify", Map.of("code", code(chukaSecret))).status()).isEqualTo(200);
        }
        assertThat(chuka.get("/api/v1/session").json().at("/roles/0/role").asText()).isEqualTo("SUPER_ADMIN");

        // SA-02 to SA-05: logo straight to storage, contrast check, create, duplicate refused.
        JsonNode upload = chuka.post("/api/v1/platform/logo-uploads", Map.of("fileName", "crest.svg", "contentType", "image/svg+xml", "size", 120)).json();
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><rect width='10' height='10' fill='#1A2368'/></svg>";
        assertThat(chuka.send("PUT", upload.get("uploadUrl").asText(), svg, "image/svg+xml").status()).isEqualTo(200);
        assertThat(chuka.get("/api/v1/platform/tenants/address-available?slug=" + wesleySlug).json().at("/slug/available").asBoolean()).isTrue();

        Map<String, Object> wesley = university("Wesley University", "Wesley ODeL", wesleySlug, wesleyDomain, "Ngozi Adebayo", ngozi, "#F5D76E", "#C27808");
        Browser.Res unreadable = chuka.post("/api/v1/platform/tenants", wesley);
        assertThat(unreadable.status()).isEqualTo(422);
        ((Map<String, Object>) wesley.get("branding")).putAll(Map.of("primaryColor", "#1A2368", "logoKey", upload.get("key").asText()));
        Browser.Res created = chuka.post("/api/v1/platform/tenants", wesley);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        wesleyId = created.json().get("id").asText();
        assertThat(created.json().at("/firstAdmin/accepted").asBoolean()).isFalse();
        assertThat(created.json().at("/createdBy/fullName").asText()).isNotBlank();
        assertThat(chuka.post("/api/v1/platform/tenants", wesley).status()).isEqualTo(409);

        // The invitation email goes out (dev: logged, readable at /api/v1/dev/emails).
        JsonNode email = null;
        for (int i = 0; i < 20 && email == null; i++) {
            for (JsonNode e : chuka.get("/api/v1/dev/emails").json()) if (e.get("to_address").asText().equals(ngozi)) email = e;
            if (email == null || !email.get("status").asText().equals("SENT")) { email = null; Thread.sleep(500); }
        }
        assertThat(email).as("invitation email sent").isNotNull();
        assertThat(email.get("body").asText()).contains(wesleyHost + "/staff/sign-in").doesNotContain("{{");

        // W-01, W-04, W-05, then an empty W-10 in Wesley's colours.
        ngoziB = new Browser(gw, wesleyHost);
        JsonNode ctx = ngoziB.get("/api/v1/public/context").json();
        assertThat(ctx.get("kind").asText()).isEqualTo("UNIVERSITY");
        assertThat(ctx.at("/tenant/primaryColor").asText()).isEqualTo("#1A2368");
        assertThat(ngoziB.get(ctx.at("/tenant/logoUrl").asText()).body()).contains("<svg");
        ngoziSecret = firstSignIn(ngoziB, ngozi, null);
        JsonNode session = ngoziB.get("/api/v1/session").json();
        assertThat(session.at("/roles/0/role").asText()).isEqualTo("ODEL_ADMIN");
        assertThat(session.get("idleTimeoutSeconds").asInt()).isEqualTo(1800);
        JsonNode checklist = ngoziB.get("/api/v1/setup/checklist").json();
        assertThat(checklist.get("steps")).hasSize(9);
        assertThat(checklist.at("/steps/0/status").asText()).isEqualTo("NOT_STARTED");
        assertThat(checklist.at("/steps/7/status").asText()).isEqualTo("LOCKED");
        assertThat(ngoziB.get("/api/v1/notifications/unread-count").json().get("count").asInt()).isEqualTo(1);
        assertThat(chuka.get("/api/v1/platform/tenants/" + wesleyId).json().at("/firstAdmin/accepted").asBoolean()).isTrue();

        // S-04 sign out, then W-03 asks for the code; a trusted browser skips it next time.
        assertThat(ngoziB.post("/api/v1/auth/sign-out", Map.of()).status()).isEqualTo(204);
        assertThat(ngoziB.get("/api/v1/session").status()).isEqualTo(401);
        ngoziB.googleSignIn(ngozi);
        assertThat(ngoziB.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_REQUIRED");
        assertThat(ngoziB.get("/api/v1/setup/checklist").status()).isEqualTo(403);
        assertThat(ngoziB.post("/api/v1/auth/mfa/verify", Map.of("code", code(ngoziSecret), "trustDevice", true)).json().get("stage").asText()).isEqualTo("SIGNED_IN");
        ngoziB.post("/api/v1/auth/sign-out", Map.of());
        ngoziB.googleSignIn(ngozi);
        assertThat(ngoziB.get("/api/v1/session").json().get("stage").asText()).isEqualTo("SIGNED_IN");

        // The audit log shows the creation and the sign-ins, with IP.
        assertThat(actions(wesleyId)).containsSubsequence("TENANT_CREATED", "MFA_SET_UP", "SIGN_IN", "SIGN_OUT", "SIGN_IN");
        chuka.get("/api/v1/platform/audit?tenantId=" + wesleyId).json().get("items").forEach(r -> assertThat(r.get("ip").asText()).isNotBlank());
    }

    @Test
    @Order(2)
    void edgeCases() {
        assertThat(new Browser(gw, wesleyHost).googleSignIn("someone@gmail.com")).endsWith("/staff/sign-in?error=wrong_domain");
        assertThat(new Browser(gw, wesleyHost).googleSignIn("nobody@" + wesleyDomain)).endsWith("/staff/sign-in?error=not_linked");
        assertThat(new Browser(gw, "nowhere-" + run + "." + base).get("/api/v1/public/context").status()).isEqualTo(404);

        Browser b = new Browser(gw, wesleyHost);
        b.cookies.remove("ODEL_TRUST");
        b.googleSignIn(ngozi);
        assertThat(b.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_REQUIRED");
        assertThat(b.post("/api/v1/auth/mfa/verify", Map.of("code", "000000")).json().get("attemptsLeft").asInt()).isEqualTo(2);
        assertThat(b.post("/api/v1/auth/mfa/verify", Map.of("code", "000000")).json().get("attemptsLeft").asInt()).isEqualTo(1);
        Browser.Res third = b.post("/api/v1/auth/mfa/verify", Map.of("code", "000000"));
        assertThat(third.status()).isEqualTo(401);
        assertThat(third.json().get("error").asText()).isEqualTo("too_many_attempts");
        assertThat(b.get("/api/v1/session").status()).isEqualTo(401);
        assertThat(actions(wesleyId)).contains("MFA_FAILED");

        if (!chukaBackupCodes.isEmpty()) {
            Browser c = new Browser(gw, console);
            c.googleSignIn("chuka.obi@odel.dev");
            Browser.Res backup = c.post("/api/v1/auth/mfa/backup", Map.of("code", chukaBackupCodes.get(0)));
            assertThat(backup.json().get("backupCodesLeft").asInt()).isEqualTo(7);
            Browser d = new Browser(gw, console);
            d.googleSignIn("chuka.obi@odel.dev");
            assertThat(d.post("/api/v1/auth/mfa/backup", Map.of("code", chukaBackupCodes.get(0))).status()).isEqualTo(400);
        }

        assertThat(ngoziB.get("/api/v1/platform/tenants").status()).isEqualTo(403);
        Browser noCsrf = new Browser(gw, wesleyHost);
        noCsrf.cookies.putAll(ngoziB.cookies);
        noCsrf.cookies.put("XSRF-TOKEN", "forged");
        noCsrf.cookies.remove("XSRF-TOKEN");
        assertThat(noCsrf.sendRaw("POST", "/api/v1/notifications/read-all")).isEqualTo(403);
    }

    @Test
    @Order(3)
    void wesleyCannotReachCrestfield() {
        Browser.Res created = chuka.post("/api/v1/platform/tenants",
                university("Crestfield University", "Crestfield ODL", crestSlug, crestDomain, "Yemi Adeoye", "yemi.adeoye@" + crestDomain, "#0F6E6E", "#E07A2E"));
        assertThat(created.status()).isEqualTo(201);
        String crestId = created.json().get("id").asText();
        Browser yemi = new Browser(gw, crestHost);
        firstSignIn(yemi, "yemi.adeoye@" + crestDomain, null);
        JsonNode yemiSession = yemi.get("/api/v1/session").json();
        assertThat(yemi.get("/api/v1/public/context").json().at("/tenant/primaryColor").asText()).isEqualTo("#0F6E6E");

        Browser replay = new Browser(gw, crestHost);
        replay.cookies.putAll(ngoziB.cookies);
        assertThat(replay.get("/api/v1/session").status()).isEqualTo(401);

        List<String> ids = new ArrayList<>(List.of(crestId, yemiSession.at("/user/id").asText(), yemiSession.at("/roles/0/id").asText()));
        yemi.get("/api/v1/notifications").json().get("items").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).hasSizeGreaterThanOrEqualTo(4);
        for (String id : ids) {
            assertThat(ngoziB.post("/api/v1/notifications/" + id + "/read", Map.of()).status()).isEqualTo(404);
            assertThat(ngoziB.post("/api/v1/me/active-role", Map.of("roleAssignmentId", id)).status()).isEqualTo(404);
            assertThat(ngoziB.get("/api/v1/platform/tenants/" + id).status()).isEqualTo(403);
        }
        assertThat(yemi.get("/api/v1/notifications/unread-count").json().get("count").asInt()).isEqualTo(1);
        assertThat(ngoziB.get("/api/v1/notifications").body()).doesNotContain("Crestfield");
    }
}
