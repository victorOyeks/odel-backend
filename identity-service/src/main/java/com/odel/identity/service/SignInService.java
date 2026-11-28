package com.odel.identity.service;

import com.odel.identity.client.TenantInfoClient;

import com.odel.identity.entity.RoleAssignment;

import com.odel.identity.dto.SessionView;

import com.odel.identity.entity.User;

import com.odel.identity.dto.SignInIdentity;
import com.odel.identity.client.MicrosoftClient;
import com.odel.common.security.StaffSignIn;

import com.odel.identity.client.GoogleClient;
import com.odel.identity.repository.UserRepository;
import com.odel.identity.repository.RoleAssignmentRepository;
import com.odel.identity.repository.TrustedDeviceRepository;
import com.odel.identity.dto.TenantInfo;

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
 * Staff sign-in (D1 flow C): the first step proves who the person is (Google, Microsoft or an emailed code, as the
 * university chose), then the authenticator two-step, then the role. Google and Microsoft call back to one central URL
 * each; we then hand the browser back to its university's host with a one-time code, so each university keeps its own
 * first-party session cookie. The emailed code ({@link StaffEmailCodeService}) runs on the university's host and opens
 * the session through {@link #openStaffSession}.
 */
@Service
public class SignInService {
    private static final Logger log = LoggerFactory.getLogger(SignInService.class);
    private static final String STATE = "odel:oauth:state:";
    private static final String HANDOFF = "odel:oauth:handoff:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final OdelProperties props;
    private final TenantInfoClient tenants;
    private final UserRepository users;
    private final RoleAssignmentRepository roles;
    private final TrustedDeviceRepository trusted;
    private final SessionStore sessions;
    private final SessionCookies cookies;
    private final AuditService audit;
    private final GoogleClient google;
    private final MicrosoftClient microsoft;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SignInService(StringRedisTemplate redis, ObjectMapper json, OdelProperties props, TenantInfoClient tenants,
                         UserRepository users, RoleAssignmentRepository roles, TrustedDeviceRepository trusted,
                         SessionStore sessions, SessionCookies cookies, AuditService audit, GoogleClient google,
                         MicrosoftClient microsoft, TransactionTemplate tx, Clock clock) {
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
        this.microsoft = microsoft;
        this.tx = tx;
        this.clock = clock;
    }

    /** provider: GOOGLE or MICROSOFT (null in states written before Microsoft existed = GOOGLE). */
    public record OAuthState(UUID tenantId, String host, String returnTo, String nonce, String provider) {
        public StaffSignIn method() { return StaffSignIn.orGoogle(provider); }
    }

    record Handoff(UUID tenantId, UUID userId, String returnTo, String provider) {}

    // ---- 1. start ----

    /**
     * Where the browser goes next: the provider's sign-in page (or the dev stand-in), or back to the sign-in page with an
     * error when this university does not use that method.
     */
    public String start(ResolvedHost host, String returnTo, StaffSignIn provider) {
        TenantInfo tenant = tenants.fresh(host.tenantId());
        String signIn = tenant.isPlatform() ? "/sign-in" : "/staff/sign-in";
        if (provider == StaffSignIn.EMAIL_CODE || tenant.method() != provider) return signIn + "?error=wrong_method";
        if (provider == StaffSignIn.MICROSOFT && !props.google().devStub() && !microsoft.configured()) {
            return signIn + "?error=method_unavailable";
        }
        String state = Tokens.random(24);
        String nonce = Tokens.random(16);
        String safeReturn = safeReturnTo(returnTo, tenant);
        put(STATE + state, new OAuthState(tenant.id(), host.host(), safeReturn, nonce, provider.name()), Duration.ofMinutes(10));
        if (props.google().devStub()) {
            return "/api/v1/auth/dev/" + (provider == StaffSignIn.MICROSOFT ? "microsoft" : "google") + "?state=" + state;
        }
        String domain = tenant.isPlatform() ? null : tenant.staffEmailDomain();
        return provider == StaffSignIn.MICROSOFT ? microsoft.authorizationUrl(state, nonce, domain) : google.authorizationUrl(state, nonce, domain);
    }

    // ---- 2. the provider's answer (central callback) ----

    public String callback(StaffSignIn provider, String state, String code, String error) {
        Optional<OAuthState> s = take(STATE + state, OAuthState.class);
        if (s.isEmpty() || s.get().method() != provider) return null; // unknown or expired state: caller shows a plain page
        if (error != null || code == null) return errorUrl(s.get(), "cancelled");
        SignInIdentity identity;
        try {
            identity = provider == StaffSignIn.MICROSOFT ? microsoft.exchange(code, s.get().nonce()) : google.exchange(code, s.get().nonce());
        } catch (Exception e) {
            log.warn("{} sign-in failed: {}", provider.firstFactor(), e.getMessage());
            return errorUrl(s.get(), "expired");
        }
        return afterIdentity(s.get(), identity);
    }

    /** Dev stand-in for Google or Microsoft: the email typed is treated as a verified account of that provider. */
    public String devCallback(StaffSignIn provider, String state, String email) {
        Optional<OAuthState> s = take(STATE + state, OAuthState.class);
        if (s.isEmpty()) return null;
        if (s.get().method() != provider) return errorUrl(s.get(), "expired");
        return afterIdentity(s.get(), new SignInIdentity(email == null ? null : email.trim(), true, null));
    }

    public Optional<OAuthState> peekState(String state) {
        String raw = redis.opsForValue().get(STATE + state);
        if (raw == null) return Optional.empty();
        try { return Optional.of(json.readValue(raw, OAuthState.class)); } catch (Exception e) { return Optional.empty(); }
    }

    private String afterIdentity(OAuthState s, SignInIdentity identity) {
        return TenantContext.runAs(s.tenantId(), () -> tx.execute(status -> {
            TenantInfo tenant = tenants.fresh(s.tenantId());
            String email = identity.email() == null ? "" : identity.email().toLowerCase();
            if (!tenant.isPlatform() && "SUSPENDED".equals(tenant.status())) {
                return fail(s, tenant, email, "university_suspended");
            }
            // The provider changed the university's method while this person was on Google's or Microsoft's page.
            if (tenant.method() != s.method()) return fail(s, tenant, email, "wrong_method");
            if (!identity.emailVerified()) return fail(s, tenant, email, "not_linked");
            if (!tenant.isPlatform() && !StaffEmailRules.onDomain(email, tenant.staffEmailDomain())) {
                return fail(s, tenant, email, "wrong_domain");
            }
            Optional<User> user = users.findByEmail(email);
            Optional<String> refused = refusal(user);
            if (refused.isPresent()) return fail(s, tenant, email, refused.get());
            String handoff = Tokens.random(24);
            put(HANDOFF + handoff, new Handoff(s.tenantId(), user.get().getId(), s.returnTo(), s.provider()), Duration.ofSeconds(60));
            return props.webScheme() + "://" + s.host() + "/api/v1/auth/handoff?code=" + handoff;
        }));
    }

    /**
     * Why a staff account may not sign in (not_linked, suspended, no_role), or empty when it may. The same rules for every
     * method. Students sign in with matric number and password, never here.
     */
    public Optional<String> refusal(Optional<User> user) {
        if (user.isEmpty() || !user.get().isStaff()) return Optional.of("not_linked");
        if (user.get().getStatus() == User.Status.SUSPENDED) return Optional.of("suspended");
        if (roles.findByUserIdOrderByCreatedAt(user.get().getId()).isEmpty()) return Optional.of("no_role");
        return Optional.empty();
    }

    private String fail(OAuthState s, TenantInfo tenant, String email, String code) {
        refused(tenant.id(), email, code, s.method());
        return errorUrl(s, code);
    }

    /** Audit line for a refused staff sign-in (the address as typed or as the provider gave it). */
    public void refused(UUID tenantId, String email, String code, StaffSignIn method) {
        audit.record(new AuditService.Entry(tenantId, new AuditService.Actor(null, null, email, null),
                "SIGN_IN_FAILED", "user", null, "Sign-in refused for " + email + " (" + code + ", " + method.label() + ")", null,
                Map.of("reason", code, "method", method.name())));
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
            User user = users.findInTenant(h.get().userId()).orElse(null);
            if (user == null) return signIn + "?error=not_linked";
            return openStaffSession(user, host.tenantId(), h.get().returnTo(), StaffSignIn.orGoogle(h.get().provider()), request, response);
        });
    }

    /**
     * The first step passed: opens the half-way session (two-step set-up, two-step code, or straight in on a trusted
     * browser) and returns where the browser goes next (/auth/continue). Runs inside a transaction.
     */
    public String openStaffSession(User user, UUID tenantId, String returnTo, StaffSignIn method,
                                   HttpServletRequest request, HttpServletResponse response) {
        SessionData data = new SessionData();
        data.userId = user.getId();
        data.tenantId = tenantId;
        data.ip = RequestInfo.ip();
        data.userAgent = RequestInfo.userAgent();
        data.firstFactor = method.firstFactor();
        if (!user.mfaEnabled()) {
            data.stage = SessionData.Stage.MFA_SETUP;
        } else if (trustedDevice(request, user)) {
            chooseRoleStage(user, data);
            signedIn(user, data, method.firstFactor() + " and a trusted browser");
        } else {
            data.stage = SessionData.Stage.MFA_REQUIRED;
        }
        cookies.setSession(response, sessions.create(data));
        return "/auth/continue?next=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
    }

    /** "/staff" or a path on the same host. */
    public String safeReturnTo(String returnTo, TenantInfo tenant) {
        String fallback = tenant.isPlatform() ? "/" : "/staff";
        if (returnTo == null || !returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.contains("\\")) return fallback;
        return returnTo;
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
