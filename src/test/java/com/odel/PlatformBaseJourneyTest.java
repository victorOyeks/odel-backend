package com.odel;

import com.fasterxml.jackson.databind.JsonNode;
import com.odel.identity.Totp;
import com.odel.jobs.JobWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B0 end-to-end test (presenter's guide, Build plan, B0): the provider creates Wesley on an empty platform,
 * Wesley's first admin signs in with Google, sets up two-step sign-in and lands on an empty setup checklist
 * in Wesley's colours; sign out and back in asks for the code; the audit log shows it all. Then the edge
 * cases and tenant isolation against a second university, Crestfield.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformBaseJourneyTest extends IntegrationTest {
    static final String CONSOLE = "console.localhost:3000";
    static final String WESLEY = "wesley.localhost:3000";
    static final String CRESTFIELD = "crestfield.localhost:3000";
    static final String NGOZI = "ngozi.adebayo@wesleyuni.edu.ng";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobWorker worker;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    String chukaSecret, ngoziSecret;
    List<String> chukaBackupCodes;
    Browser chuka, ngozi;
    String wesleyId;

    /** A code works only once, so each sign-in takes a different step inside the accepted window. */
    String uniqueCode(String secret, Set<String> used) {
        long now = Totp.step(Instant.now());
        for (long s = now - 1; s <= now + 1; s++) {
            String c = Totp.code(secret, s);
            if (used.add(secret + c)) return c;
        }
        throw new IllegalStateException("No unused code in the window");
    }

    final Set<String> usedCodes = new HashSet<>();

    /** First sign-in: Google, then set up two-step sign-in. Returns the authenticator secret. */
    String firstSignIn(Browser b, String email, List<String> backupOut) {
        assertThat(b.googleSignIn(email)).startsWith("/auth/continue?next=");
        assertThat(b.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_SETUP");
        JsonNode setup = b.post("/api/v1/auth/mfa/setup", Map.of()).json();
        String secret = setup.get("secret").asText();
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + secret);
        assertThat(b.post("/api/v1/auth/mfa/setup", Map.of()).json().get("secret").asText()).isEqualTo(secret);
        Browser.Res confirmed = b.post("/api/v1/auth/mfa/setup/confirm", Map.of("code", uniqueCode(secret, usedCodes)));
        assertThat(confirmed.status()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        confirmed.json().get("backupCodes").forEach(c -> codes.add(c.asText()));
        assertThat(codes).hasSize(8).allMatch(c -> c.matches("\\d{4}-\\d{4}"));
        if (backupOut != null) backupOut.addAll(codes);
        assertThat(confirmed.json().at("/session/stage").asText()).isEqualTo("SIGNED_IN");
        return secret;
    }

    List<String> auditActions(String tenantId) {
        return jdbc.queryForList("select action from audit_log where tenant_id = ?::uuid order by created_at", String.class, tenantId);
    }

    @Test
    @Order(1)
    void providerCreatesWesleyAndItsAdminSignsIn() {
        // 1. Chuka Obi opens SA-01 on an empty platform.
        chuka = new Browser(port, CONSOLE);
        assertThat(chuka.get("/api/v1/platform/tenants").status()).isEqualTo(401);
        chukaBackupCodes = new ArrayList<>();
        chukaSecret = firstSignIn(chuka, "chuka.obi@odel.dev", chukaBackupCodes);
        JsonNode s = chuka.get("/api/v1/session").json();
        assertThat(s.at("/roles/0/role").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(chuka.get("/api/v1/platform/tenants").json().get("items")).isEmpty();

        // 2. He creates Wesley University (SA-02 to SA-05). Logo straight to storage.
        JsonNode upload = chuka.post("/api/v1/platform/logo-uploads", Map.of("fileName", "crest.svg", "contentType", "image/svg+xml", "size", 120)).json();
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><rect width='10' height='10' fill='#1A2368'/></svg>";
        assertThat(chuka.send("PUT", upload.get("uploadUrl").asText(), svg, "image/svg+xml").status()).isEqualTo(200);
        assertThat(chuka.get("/api/v1/platform/tenants/address-available?slug=wesley&webAddress=odel.wesleyuni.edu.ng").json()
                .at("/slug/available").asBoolean()).isTrue();

        Map<String, Object> wesley = new LinkedHashMap<>();
        wesley.put("name", "Wesley University");
        wesley.put("shortName", "Wesley ODeL");
        wesley.put("slug", "wesley");
        wesley.put("webAddress", "odel.wesleyuni.edu.ng");
        wesley.put("googleDomain", "wesleyuni.edu.ng");
        wesley.put("firstAdmin", Map.of("fullName", "Ngozi Adebayo", "email", NGOZI));
        wesley.put("branding", Map.of("primaryColor", "#F5D76E", "accentColor", "#C27808", "logoKey", upload.get("key").asText()));
        Browser.Res unreadable = chuka.post("/api/v1/platform/tenants", wesley);
        assertThat(unreadable.status()).isEqualTo(422);                              // SA-03b
        assertThat(unreadable.json().get("error").asText()).isEqualTo("contrast");

        wesley.put("branding", Map.of("primaryColor", "#1A2368", "accentColor", "#C27808", "logoKey", upload.get("key").asText()));
        Browser.Res created = chuka.post("/api/v1/platform/tenants", wesley);
        assertThat(created.status()).isEqualTo(201);                                 // SA-05b
        JsonNode t = created.json();
        wesleyId = t.get("id").asText();
        assertThat(t.get("status").asText()).isEqualTo("SETTING_UP");
        assertThat(t.at("/firstAdmin/accepted").asBoolean()).isFalse();
        assertThat(t.at("/createdBy/fullName").asText()).isEqualTo("Chuka Obi");
        assertThat(chuka.post("/api/v1/platform/tenants", wesley).status()).isEqualTo(409); // SA-02b

        // The invitation email is queued, then sent by the worker.
        Map<String, Object> email = jdbc.queryForMap("select to_address, body, status from outbound_messages where tenant_id = ?::uuid and channel = 'EMAIL'", wesleyId);
        assertThat(email.get("to_address")).isEqualTo(NGOZI);
        assertThat((String) email.get("body")).contains("odel.wesleyuni.edu.ng/staff/sign-in").doesNotContain("{{");
        worker.runDue(10);
        assertThat(jdbc.queryForObject("select status from outbound_messages where tenant_id = ?::uuid and channel = 'EMAIL'", String.class, wesleyId)).isEqualTo("SENT");

        // 3–5. Mrs Adebayo signs in with Google (W-01), sets up two-step (W-04, W-05), lands on an empty W-10 in Wesley's colours.
        ngozi = new Browser(port, WESLEY);
        JsonNode ctx = ngozi.get("/api/v1/public/context").json();
        assertThat(ctx.get("kind").asText()).isEqualTo("UNIVERSITY");
        assertThat(ctx.at("/tenant/primaryColor").asText()).isEqualTo("#1A2368");
        assertThat(ctx.at("/tenant/logoUrl").asText()).startsWith("/api/v1/dev/storage/logos/");
        assertThat(ngozi.get(ctx.at("/tenant/logoUrl").asText()).body()).contains("<svg");
        assertThat(new Browser(port, "odel.wesleyuni.edu.ng").get("/api/v1/public/context").json().at("/tenant/slug").asText()).isEqualTo("wesley");

        ngoziSecret = firstSignIn(ngozi, NGOZI, null);
        JsonNode session = ngozi.get("/api/v1/session").json();
        assertThat(session.at("/roles/0/role").asText()).isEqualTo("ODEL_ADMIN");
        assertThat(session.get("activeRoleId").asText()).isEqualTo(session.at("/roles/0/id").asText());
        assertThat(session.get("idleTimeoutSeconds").asInt()).isEqualTo(1800);

        JsonNode checklist = ngozi.get("/api/v1/setup/checklist").json();
        assertThat(checklist.get("steps")).hasSize(9);
        assertThat(checklist.get("done").asInt()).isZero();
        assertThat(checklist.at("/steps/0/status").asText()).isEqualTo("NOT_STARTED");
        assertThat(checklist.at("/steps/7/status").asText()).isEqualTo("LOCKED");
        assertThat(ngozi.get("/api/v1/notifications/unread-count").json().get("count").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from users where lower(email) = ?", String.class, NGOZI)).isEqualTo("ACTIVE");
        assertThat(chuka.get("/api/v1/platform/tenants/" + wesleyId).json().at("/firstAdmin/accepted").asBoolean()).isTrue();

        // 6. She signs out (S-04) and back in: W-03 asks for the code.
        assertThat(ngozi.post("/api/v1/auth/sign-out", Map.of()).status()).isEqualTo(204);
        assertThat(ngozi.get("/api/v1/session").status()).isEqualTo(401);
        ngozi.googleSignIn(NGOZI);
        assertThat(ngozi.get("/api/v1/session").json().get("stage").asText()).isEqualTo("MFA_REQUIRED");
        assertThat(ngozi.get("/api/v1/setup/checklist").status()).isEqualTo(403);    // not signed in until the code
        Browser.Res verified = ngozi.post("/api/v1/auth/mfa/verify", Map.of("code", uniqueCode(ngoziSecret, usedCodes), "trustDevice", true));
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.json().get("stage").asText()).isEqualTo("SIGNED_IN");
        assertThat(ngozi.cookies).containsKey("ODEL_TRUST");

        // A trusted browser skips W-03 for 30 days.
        ngozi.post("/api/v1/auth/sign-out", Map.of());
        ngozi.googleSignIn(NGOZI);
        assertThat(ngozi.get("/api/v1/session").json().get("stage").asText()).isEqualTo("SIGNED_IN");

        // 7. The audit log shows Wesley's creation and the sign-ins, with time and IP.
        assertThat(auditActions(wesleyId)).containsSubsequence("TENANT_CREATED", "MFA_SET_UP", "SIGN_IN", "SIGN_OUT", "SIGN_IN");
        JsonNode audit = chuka.get("/api/v1/platform/audit?tenantId=" + wesleyId).json();
        assertThat(audit.get("total").asInt()).isGreaterThanOrEqualTo(6);
        audit.get("items").forEach(row -> assertThat(row.get("ip").asText()).isNotBlank());
        assertThat(chuka.get("/api/v1/platform/audit").json().get("items").toString()).contains("Chuka Obi signed in");
    }

    @Test
    @Order(2)
    void edgeCases() {
        // W-02: a Google account that is not a Wesley staff record, or outside the university's domain.
        assertThat(new Browser(port, WESLEY).googleSignIn("someone@gmail.com")).endsWith("/staff/sign-in?error=wrong_domain");
        assertThat(new Browser(port, WESLEY).googleSignIn("nobody@wesleyuni.edu.ng")).endsWith("/staff/sign-in?error=not_linked");
        assertThat(new Browser(port, CONSOLE).googleSignIn(NGOZI)).endsWith("/sign-in?error=not_linked");
        assertThat(new Browser(port, "nowhere.localhost:3000").get("/api/v1/auth/google/start").status()).isEqualTo(404);

        // Wrong code three times ends the sign-in.
        Browser b = new Browser(port, CONSOLE);
        b.googleSignIn("chuka.obi@odel.dev");
        JsonNode first = b.post("/api/v1/auth/mfa/verify", Map.of("code", "000000")).json();
        assertThat(first.get("error").asText()).isEqualTo("wrong_code");
        assertThat(first.get("attemptsLeft").asInt()).isEqualTo(2);
        assertThat(b.post("/api/v1/auth/mfa/verify", Map.of("code", "000000")).json().get("attemptsLeft").asInt()).isEqualTo(1);
        Browser.Res third = b.post("/api/v1/auth/mfa/verify", Map.of("code", "000000"));
        assertThat(third.status()).isEqualTo(401);
        assertThat(third.json().get("error").asText()).isEqualTo("too_many_attempts");
        assertThat(b.get("/api/v1/session").status()).isEqualTo(401);
        assertThat(auditActions("00000000-0000-0000-0000-000000000001")).contains("MFA_FAILED");

        // A backup code works once (W-03 "Use a backup code instead").
        Browser c = new Browser(port, CONSOLE);
        c.googleSignIn("chuka.obi@odel.dev");
        Browser.Res backup = c.post("/api/v1/auth/mfa/backup", Map.of("code", chukaBackupCodes.get(0)));
        assertThat(backup.status()).isEqualTo(200);
        assertThat(backup.json().get("backupCodesLeft").asInt()).isEqualTo(7);
        Browser d = new Browser(port, CONSOLE);
        d.googleSignIn("chuka.obi@odel.dev");
        assertThat(d.post("/api/v1/auth/mfa/backup", Map.of("code", chukaBackupCodes.get(0))).status()).isEqualTo(400);

        // A code cannot be replayed.
        Browser e = new Browser(port, CONSOLE);
        e.googleSignIn("chuka.obi@odel.dev");
        String code = uniqueCode(chukaSecret, usedCodes);
        assertThat(e.post("/api/v1/auth/mfa/verify", Map.of("code", code)).status()).isEqualTo(200);
        Browser f = new Browser(port, CONSOLE);
        f.googleSignIn("chuka.obi@odel.dev");
        assertThat(f.post("/api/v1/auth/mfa/verify", Map.of("code", code)).status()).isEqualTo(400);

        // Roles: a university admin cannot use the provider console API; no CSRF header is refused.
        assertThat(ngozi.get("/api/v1/platform/tenants").status()).isIn(401, 403);
        assertThat(sendWithoutCsrf(ngozi, "/api/v1/notifications/read-all")).isEqualTo(403);

        // Idle session (S-01, S-02): the server keeps it 30 minutes from the last request.
        JsonNode s = ngozi.get("/api/v1/session").json();
        long left = java.time.Duration.between(Instant.now(), java.time.OffsetDateTime.parse(s.get("idleExpiresAt").asText()).toInstant()).toSeconds();
        assertThat(left).isBetween(1700L, 1800L);
    }

    int sendWithoutCsrf(Browser b, String path) {
        try {
            var http = java.net.http.HttpClient.newHttpClient();
            StringBuilder cookie = new StringBuilder();
            b.cookies.forEach((k, v) -> cookie.append(cookie.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
                    .header("X-Odel-Host", b.host).header("Cookie", cookie.toString()).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
            return http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    @Order(3)
    void wesleyCannotReachCrestfield() {
        Map<String, Object> crest = new LinkedHashMap<>();
        crest.put("name", "Crestfield University");
        crest.put("shortName", "Crestfield ODL");
        crest.put("slug", "crestfield");
        crest.put("webAddress", "odl.crestfield.edu.ng");
        crest.put("googleDomain", "crestfield.edu.ng");
        crest.put("firstAdmin", Map.of("fullName", "Yemi Adeoye", "email", "yemi.adeoye@crestfield.edu.ng"));
        crest.put("branding", Map.of("primaryColor", "#0F6E6E", "accentColor", "#E07A2E"));
        Browser.Res created = chuka.post("/api/v1/platform/tenants", crest);
        assertThat(created.status()).isEqualTo(201);
        String crestId = created.json().get("id").asText();

        Browser yemi = new Browser(port, CRESTFIELD);
        firstSignIn(yemi, "yemi.adeoye@crestfield.edu.ng", null);
        assertThat(yemi.get("/api/v1/setup/checklist").status()).isEqualTo(200);
        assertThat(yemi.get("/api/v1/public/context").json().at("/tenant/primaryColor").asText()).isEqualTo("#0F6E6E");

        // Wesley's session cookie means nothing on Crestfield's host.
        Browser replay = new Browser(port, CRESTFIELD);
        replay.cookies.putAll(ngozi.cookies);
        assertThat(replay.get("/api/v1/session").status()).isEqualTo(401);
        // The same Wesley email is unknown at Crestfield.
        assertThat(new Browser(port, CRESTFIELD).googleSignIn(NGOZI)).contains("error=wrong_domain");

        // Every endpoint with an id, called by Wesley's admin with each of Crestfield's ids, returns nothing.
        List<String> ids = new ArrayList<>(List.of(crestId));
        for (String table : List.of("users", "role_assignments", "notifications", "mfa_backup_codes", "trusted_devices", "push_tokens", "outbound_messages")) {
            ids.addAll(jdbc.queryForList("select id::text from " + table + " where tenant_id = ?::uuid", String.class, crestId));
        }
        assertThat(ids).hasSizeGreaterThan(10);
        int calls = 0;
        for (var entry : mappings.getHandlerMethods().keySet()) {
            for (String pattern : entry.getPatternValues()) {
                if (!pattern.contains("{") || pattern.contains("**")) continue;
                Set<RequestMethod> methods = entry.getMethodsCondition().getMethods();
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    for (String id : ids) {
                        String path = pattern.replaceAll("\\{[^}]+}", id);
                        Browser.Res r = ngozi.send(m.name(), path, m == RequestMethod.GET ? null : "{}", "application/json");
                        assertThat(r.status() / 100).as(m + " " + path + " -> " + r.status()).isNotEqualTo(2);
                        calls++;
                    }
                }
            }
        }
        assertThat(calls).isGreaterThan(0);

        // Role switch with Crestfield's role id is refused.
        String yemiRole = jdbc.queryForObject("select id::text from role_assignments where tenant_id = ?::uuid", String.class, crestId);
        assertThat(ngozi.post("/api/v1/me/active-role", Map.of("roleAssignmentId", yemiRole)).status()).isEqualTo(404);
        // Wesley's lists hold none of Crestfield's rows.
        assertThat(ngozi.get("/api/v1/notifications").body()).doesNotContain("Crestfield");
    }
}
