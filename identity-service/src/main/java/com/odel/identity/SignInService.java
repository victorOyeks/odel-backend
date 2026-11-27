package com.odel.identity;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.audit.AuditService;
import com.odel.common.OdelProperties;
import com.odel.common.RequestInfo;
import com.odel.common.Tokens;
import com.odel.common.tenancy.ResolvedHost;
import com.odel.common.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Staff sign-in (D1 flow C): Google proves who the person is, then two-step code, then the role.
 * Google calls back to one central URL; we then hand the browser back to its university's host with a
 * one-time code, so each university keeps its own first-party session cookie.
 */
@Service
public class SignInService {
    private static final Logger log = LoggerFactory.getLogger(SignInService.class);
    private static final String STATE = "odel:oauth:state:";
    private static final String HANDOFF = "odel:oauth:handoff:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final OdelProperties props;
    private final TenantInfo.Client tenants;
    private final UserRepository users;
    private final RoleAssignmentRepository roles;
    private final TrustedDeviceRepository trusted;
    private final SessionStore sessions;
    private final SessionCookies cookies;
    private final AuditService audit;
    private final GoogleClient google;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SignInService(StringRedisTemplate redis, ObjectMapper json, OdelProperties props, TenantInfo.Client tenants,
                         UserRepository users, RoleAssignmentRepository roles, TrustedDeviceRepository trusted,
                         SessionStore sessions, SessionCookies cookies, AuditService audit, GoogleClient google,
                         TransactionTemplate tx, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.props = props;
        this.tenants = tenants;
        this.users = users;
        this.roles = roles;
        this.trusted = trusted;
        this.sessions = sessions;
        this.cookies = cookies;
        this.audit = audit;
        this.google = google;
        this.tx = tx;
        this.clock = clock;
    }

    public record OAuthState(UUID tenantId, String host, String returnTo, String nonce) {}

    record Handoff(UUID tenantId, UUID userId, String returnTo) {}

    // ---- 1. start ----

    public String start(ResolvedHost host, String returnTo) {
        String state = Tokens.random(24);
        String nonce = Tokens.random(16);
        TenantInfo tenant = tenants.get(host.tenantId());
        String safeReturn = safeReturnTo(returnTo, tenant);
        put(STATE + state, new OAuthState(tenant.id(), host.host(), safeReturn, nonce), Duration.ofMinutes(10));
        if (props.google().devStub()) {
            return "/api/v1/auth/dev/google?state=" + state;
        }
        String hd = tenant.isPlatform() ? null : tenant.googleDomain();
        return google.authorizationUrl(state, nonce, hd);
    }

    // ---- 2. Google's answer (central callback) ----

    public String callback(String state, String code, String error) {
        Optional<OAuthState> s = take(STATE + state, OAuthState.class);
        if (s.isEmpty()) return null; // unknown or expired state: caller shows a plain page
        if (error != null || code == null) return errorUrl(s.get(), "cancelled");
        GoogleIdentity identity;
        try {
            identity = google.exchange(code, s.get().nonce());
        } catch (Exception e) {
            log.warn("Google sign-in failed: {}", e.getMessage());
            return errorUrl(s.get(), "expired");
        }
        return afterGoogle(s.get(), identity);
    }

    /** Dev stub stands in for Google: the email typed is treated as a verified Google account. */
    public String devCallback(String state, String email) {
        Optional<OAuthState> s = take(STATE + state, OAuthState.class);
        if (s.isEmpty()) return null;
        String domain = email.contains("@") ? email.substring(email.indexOf('@') + 1) : null;
        return afterGoogle(s.get(), new GoogleIdentity(email.trim(), true, domain, null));
    }

    public Optional<OAuthState> peekState(String state) {
        String raw = redis.opsForValue().get(STATE + state);
        if (raw == null) return Optional.empty();
        try { return Optional.of(json.readValue(raw, OAuthState.class)); } catch (Exception e) { return Optional.empty(); }
    }

    private String afterGoogle(OAuthState s, GoogleIdentity identity) {
        return TenantContext.runAs(s.tenantId(), () -> tx.execute(status -> {
            TenantInfo tenant = tenants.get(s.tenantId());
            String email = identity.email() == null ? "" : identity.email().toLowerCase();
            if (!tenant.isPlatform() && "SUSPENDED".equals(tenant.status())) {
                return fail(s, tenant, email, "university_suspended");
            }
            if (!identity.emailVerified()) return fail(s, tenant, email, "not_linked");
            if (!tenant.isPlatform() && tenant.googleDomain() != null
                    && !identity.emailDomain().equalsIgnoreCase(tenant.googleDomain())) {
                return fail(s, tenant, email, "wrong_domain");
            }
            Optional<User> user = users.findByEmail(email);
            if (user.isEmpty()) return fail(s, tenant, email, "not_linked");
            // Students sign in with matric number and password, never with Google (B1).
            if (user.get().getAuthType() != User.AuthType.GOOGLE) return fail(s, tenant, email, "not_linked");
            if (user.get().getStatus() == User.Status.SUSPENDED) return fail(s, tenant, email, "suspended");
            if (roles.findByUserIdOrderByCreatedAt(user.get().getId()).isEmpty()) return fail(s, tenant, email, "no_role");
            String handoff = Tokens.random(24);
            put(HANDOFF + handoff, new Handoff(s.tenantId(), user.get().getId(), s.returnTo()), Duration.ofSeconds(60));
            return props.webScheme() + "://" + s.host() + "/api/v1/auth/handoff?code=" + handoff;
        }));
    }

    private String fail(OAuthState s, TenantInfo tenant, String email, String code) {
        audit.record(new AuditService.Entry(tenant.id(), new AuditService.Actor(null, null, email, null),
                "SIGN_IN_FAILED", "user", null, "Sign-in refused for " + email + " (" + code + ")", null, Map.of("reason", code)));
        return errorUrl(s, code);
    }

    private String errorUrl(OAuthState s, String code) {
        String error = "?error=" + code;
        boolean platform = s.tenantId().equals(TenantContext.PLATFORM_TENANT_ID);
        return props.webScheme() + "://" + s.host() + (platform ? "/sign-in" : "/staff/sign-in") + error;
    }

    // ---- 3. back on the university's host ----

    public String handoff(String code, ResolvedHost host, HttpServletRequest request, HttpServletResponse response) {
        boolean platform = host.isPlatform();
        String signIn = platform ? "/sign-in" : "/staff/sign-in";
        Optional<Handoff> h = take(HANDOFF + code, Handoff.class);
        if (h.isEmpty() || !host.known() || !h.get().tenantId().equals(host.tenantId())) {
            return signIn + "?error=expired";
        }
        return tx.execute(status -> {
            User user = users.findById(h.get().userId()).orElse(null);
            if (user == null) return signIn + "?error=not_linked";
            SessionData data = new SessionData();
            data.userId = user.getId();
            data.tenantId = host.tenantId();
            data.ip = RequestInfo.ip();
            data.userAgent = RequestInfo.userAgent();
            if (!user.mfaEnabled()) {
                data.stage = SessionData.Stage.MFA_SETUP;
            } else if (trustedDevice(request, user)) {
                chooseRoleStage(user, data);
                signedIn(user, data, "Google and a trusted browser");
            } else {
                data.stage = SessionData.Stage.MFA_REQUIRED;
            }
            cookies.setSession(response, sessions.create(data));
            String next = h.get().returnTo();
            return "/auth/continue?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
        });
    }

    private boolean trustedDevice(HttpServletRequest request, User user) {
        String token = SessionCookies.read(request, SessionCookies.TRUST);
        if (token == null) return false;
        return trusted.findByTokenHash(Tokens.sha256(token))
                .filter(d -> d.getUserId().equals(user.getId()) && d.getExpiresAt().isAfter(clock.instant()))
                .map(d -> { d.setLastUsedAt(clock.instant()); return true; })
                .orElse(false);
    }

    /** Sets the stage after the second factor: one role signs straight in, several use the remembered one or ask. */
    public void chooseRoleStage(User user, SessionData data) {
        List<RoleAssignment> mine = roles.findByUserIdOrderByCreatedAt(user.getId());
        if (mine.size() == 1) {
            data.activeRoleId = mine.get(0).getId();
            data.activeRole = mine.get(0).getRole();
            data.stage = SessionData.Stage.SIGNED_IN;
            return;
        }
        Optional<RoleAssignment> last = mine.stream().filter(r -> r.getId().equals(user.getLastRoleAssignmentId())).findFirst();
        if (last.isPresent()) {
            data.activeRoleId = last.get().getId();
            data.activeRole = last.get().getRole();
            data.stage = SessionData.Stage.SIGNED_IN;
        } else {
            data.activeRoleId = null;
            data.activeRole = null;
            data.stage = SessionData.Stage.CHOOSE_ROLE;
        }
    }

    /** The second factor passed: record the sign-in and accept a pending invitation. */
    public void signedIn(User user, SessionData data, String method) {
        Instant now = clock.instant();
        user.setLastSignInAt(now);
        user.setUpdatedAt(now);
        if (user.getStatus() == User.Status.INVITED) {
            user.setStatus(User.Status.ACTIVE);
            user.setAcceptedAt(now);
        }
        users.save(user);
        audit.record(new AuditService.Entry(data.tenantId, actor(user, data), "SIGN_IN", "user", user.getId(),
                user.getFullName() + " signed in (" + method + ")", null, null));
    }

    public AuditService.Actor actor(User user, SessionData data) {
        String role = data.activeRole == null ? null : data.activeRole.name();
        return new AuditService.Actor(user.getId(), user.getTenantId(), user.getFullName(), role);
    }

    // ---- views ----

    public SessionView view(String sessionKey, SessionData data) {
        User user = users.findById(data.userId).orElseThrow();
        TenantInfo tenant = tenants.get(data.tenantId);
        List<SessionView.RoleView> list = roles.findByUserIdOrderByCreatedAt(user.getId()).stream()
                .map(r -> new SessionView.RoleView(r.getId(), r.getRole().name(), r.getRole().label(), r.getScopeType().name(),
                        r.getScopeId(), r.getScopeLabel() != null ? r.getScopeLabel() : tenant.name()))
                .toList();
        Long left = sessions.remainingSeconds(sessionKey);
        long ttl = sessions.ttl(data).toSeconds();
        Instant expires = clock.instant().plusSeconds(left == null || left < 0 ? ttl : left);
        return new SessionView(data.stage.name(),
                new SessionView.UserView(user.getId(), user.getFullName(), user.getEmail(), user.initials()),
                list, data.activeRoleId, ttl, expires.atZone(ZoneId.of(props.timeZone())).toOffsetDateTime());
    }

    // ---- helpers ----

    private String safeReturnTo(String returnTo, TenantInfo tenant) {
        String fallback = tenant.isPlatform() ? "/" : "/staff";
        if (returnTo == null || !returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.contains("\\")) return fallback;
        return returnTo;
    }

    private void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> Optional<T> take(String key, Class<T> type) {
        String raw = redis.opsForValue().getAndDelete(key);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(json.readValue(raw, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
