package com.odel.identity.controller;

import com.odel.identity.client.TenantInfoClient;

import com.odel.identity.entity.User;
import com.odel.identity.repository.UserRepository;
import com.odel.identity.repository.RoleAssignmentRepository;
import com.odel.identity.dto.TenantInfo;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.identity.service.SignInService;
import com.odel.identity.service.Totp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Development only (odel.google.dev-stub=true, never in production): stands in for Google's account
 * picker so sign-in can be tested without a Google OAuth client, and reads authenticator codes.
 */
@RestController
@ConditionalOnProperty(name = "odel.google.dev-stub", havingValue = "true")
public class DevController {
    private final SignInService signIn;
    private final TenantInfoClient tenants;
    private final UserRepository users;
    private final RoleAssignmentRepository roles;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public DevController(SignInService signIn, TenantInfoClient tenants, UserRepository users, RoleAssignmentRepository roles,
                         org.springframework.transaction.support.TransactionTemplate tx) {
        this.signIn = signIn;
        this.tenants = tenants;
        this.users = users;
        this.roles = roles;
        this.tx = tx;
    }

    @GetMapping(value = "/api/v1/auth/dev/google", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> picker(@RequestParam String state) {
        var st = signIn.peekState(state);
        if (st.isEmpty()) return AuthController.expiredPage();
        TenantInfo t = tenants.get(st.get().tenantId());
        record Person(String name, String email, String role) {}
        List<Person> people = TenantContext.runAs(t.id(), () -> tx.execute(x -> users.findAll().stream()
                .filter(u -> u.getStatus() != User.Status.SUSPENDED && u.getAuthType() == User.AuthType.GOOGLE)
                .sorted(Comparator.comparing(User::getFullName))
                .limit(12)
                .map(u -> new Person(u.getFullName(), u.getEmail(), roles.findByUserIdOrderByCreatedAt(u.getId()).stream()
                        .map(r -> r.getRole().label()).findFirst().orElse("No role yet")))
                .toList()));
        String brand = t.primaryColor() == null ? "#1A2368" : t.primaryColor();
        String accent = t.accentColor() == null ? "#C27808" : t.accentColor();
        String initials = Arrays.stream(t.name().split("\\s+")).filter(w -> !w.isEmpty() && Character.isUpperCase(w.charAt(0)))
                .limit(2).map(w -> w.substring(0, 1)).collect(Collectors.joining());
        String portal = t.isPlatform() ? "Provider console" : "ODeL Staff Portal";
        StringBuilder rows = new StringBuilder();
        for (Person p : people) {
            String letters = Arrays.stream(p.name().split("\\s+")).limit(2).map(w -> w.substring(0, 1)).collect(Collectors.joining()).toUpperCase();
            rows.append("""
                    <button class="acct" type="submit" name="email" value="%s">
                      <span class="av">%s</span>
                      <span class="who"><b>%s</b><span>%s</span></span>
                      <span class="role">%s</span>
                    </button>""".formatted(esc(p.email()), esc(letters), esc(p.name()), esc(p.email()), esc(p.role())));
        }
        String domainHint = t.googleDomain() == null ? "name@university.edu.ng" : "name@" + t.googleDomain();
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Choose an account · %1$s</title>
                <style>
                  :root{--brand:%2$s;--accent:%3$s;--ink:#151922;--ink2:#525a6c;--ink3:#6e7689;--line:#dde0e7;--canvas:#f5f6f8}
                  *{box-sizing:border-box}body{margin:0;min-height:100vh;font-family:"Kumbh Sans",ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--ink);
                    background:radial-gradient(90%% 60%% at 0%% 0%%,color-mix(in srgb,var(--brand) 14%%,transparent),transparent 60%%),radial-gradient(70%% 50%% at 100%% 100%%,color-mix(in srgb,var(--accent) 14%%,transparent),transparent 60%%),var(--canvas);
                    display:flex;align-items:center;justify-content:center;padding:24px}
                  .card{width:100%%;max-width:460px;background:#fff;border:1px solid var(--line);border-radius:20px;overflow:hidden;box-shadow:0 1px 2px rgb(16 24 40/.04),0 20px 48px -20px rgb(16 24 40/.25)}
                  .top{background:var(--brand);color:#fff;padding:22px 28px;display:flex;align-items:center;gap:14px;position:relative}
                  .top:after{content:"";position:absolute;left:0;right:0;bottom:0;height:4px;background:var(--accent)}
                  .crest{width:48px;height:48px;border-radius:12px;background:#fff;display:flex;align-items:center;justify-content:center;padding:4px;flex-shrink:0;color:var(--brand);font-weight:700}
                  .crest img{width:100%%;height:100%%;object-fit:contain}
                  .top b{display:block;font-size:16px}.top span{font-size:13px;opacity:.8}
                  .body{padding:26px 28px 28px}
                  .tag{display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:600;letter-spacing:.06em;text-transform:uppercase;color:#7e4e05;background:#fff7eb;border:1px solid #f6dfb6;border-radius:999px;padding:4px 10px}
                  h1{font-size:24px;line-height:1.25;margin:14px 0 6px}
                  p.lead{margin:0 0 20px;color:var(--ink2);font-size:15px;line-height:1.5}
                  .list{display:flex;flex-direction:column;border:1px solid var(--line);border-radius:14px;overflow:hidden}
                  .acct{all:unset;box-sizing:border-box;display:flex;align-items:center;gap:12px;padding:12px 14px;cursor:pointer;border-top:1px solid var(--line);min-height:60px}
                  .acct:first-child{border-top:0}.acct:hover,.acct:focus-visible{background:color-mix(in srgb,var(--brand) 6%%,#fff)}
                  .acct:focus-visible{outline:3px solid color-mix(in srgb,var(--brand) 45%%,#fff);outline-offset:-3px}
                  .av{width:38px;height:38px;border-radius:999px;background:color-mix(in srgb,var(--brand) 12%%,#fff);color:var(--brand);display:flex;align-items:center;justify-content:center;font-weight:700;font-size:14px;flex-shrink:0}
                  .who{display:flex;flex-direction:column;min-width:0;flex:1}.who b{font-size:15px}.who span{font-size:13px;color:var(--ink3);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
                  .role{font-size:12px;color:var(--ink2);background:var(--canvas);border-radius:999px;padding:4px 10px;white-space:nowrap}
                  .or{display:flex;align-items:center;gap:12px;color:var(--ink3);font-size:13px;margin:22px 0 14px}.or:before,.or:after{content:"";flex:1;height:1px;background:var(--line)}
                  label{display:block;font-size:14px;font-weight:600;margin-bottom:8px}
                  .row{display:flex;gap:10px}
                  input{flex:1;min-width:0;height:48px;border:1.5px solid var(--line);border-radius:10px;padding:0 14px;font:inherit;font-size:15px}
                  input:focus{outline:none;border-color:var(--brand);box-shadow:0 0 0 3px color-mix(in srgb,var(--brand) 18%%,transparent)}
                  .go{height:48px;border:0;border-radius:10px;background:var(--brand);color:#fff;font:inherit;font-weight:600;font-size:15px;padding:0 18px;cursor:pointer}
                  .go:hover{filter:brightness(1.12)}
                  .note{margin-top:22px;display:flex;gap:10px;font-size:13px;line-height:1.5;color:var(--ink3);background:var(--canvas);border-radius:12px;padding:12px 14px}
                  .empty{padding:16px;color:var(--ink3);font-size:14px;text-align:center}
                  @media (max-width:420px){.body{padding:22px 18px}.top{padding:18px}.row{flex-direction:column}.role{display:none}}
                </style></head><body>
                <main class="card">
                  <div class="top"><span class="crest" id="crest">%9$s</span><div><b>%1$s</b><span>%5$s</span></div></div>
                  <div class="body">
                    <span class="tag">Development sign-in</span>
                    <h1>Choose an account</h1>
                    <p class="lead">to continue to %1$s. On the dev site this page stands in for Google; you still confirm with your two-step code next.</p>
                    <form method="post" action="/api/v1/auth/dev/google" onsubmit="if(window.odelSent)return false;window.odelSent=true">
                      <input type="hidden" name="state" value="%6$s">
                      <div class="list">%7$s</div>
                    </form>
                    <div class="or">or use another email</div>
                    <form method="post" action="/api/v1/auth/dev/google" onsubmit="if(window.odelSent)return false;window.odelSent=true">
                      <input type="hidden" name="state" value="%6$s">
                      <label for="email">Google account email</label>
                      <div class="row"><input id="email" name="email" type="email" required autocomplete="email" placeholder="%8$s"><button class="go" type="submit">Continue</button></div>
                    </form>
                    <div class="note"><span aria-hidden="true">ⓘ</span><span>Real Google sign-in with the university's accounts replaces this page before launch.</span></div>
                  </div>
                </main>
                <script>addEventListener("pageshow",function(){window.odelSent=false})</script>
                </body></html>""".formatted(esc(t.name()), brand, accent, esc(initials), portal, esc(state),
                rows.isEmpty() ? "<div class=\"empty\">No staff accounts yet.</div>" : rows.toString(), esc(domainHint),
                // The crest is in the page itself (stable, cached address), so it shows with the page.
                t.logoUrl() == null ? esc(initials) : "<img src=\"" + esc(t.logoUrl()) + "\" alt=\"\" width=\"40\" height=\"40\" fetchpriority=\"high\" decoding=\"sync\">");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }

    @PostMapping(value = "/api/v1/auth/dev/google", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> pick(@RequestParam String state, @RequestParam String email, jakarta.servlet.http.HttpServletRequest request) {
        String target = signIn.devCallback(state, email);
        if (target == null) {
            // The sign-in state was already used (a second click or a resubmitted form) or has expired: send the
            // person back to the sign-in page with "start again" instead of a bare error page (QA ODEL-B0-001).
            ResolvedHost host = TenantFilter.resolved(request);
            String signInPage = host != null && host.isUniversity() ? "/staff/sign-in" : "/sign-in";
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(signInPage + "?error=expired")).build();
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(target)).build();
    }

    @GetMapping("/api/v1/dev/totp")
    public Map<String, String> totp(@RequestParam String secret) {
        return Map.of("code", Totp.code(secret, Totp.step(Instant.now())));
    }
}
