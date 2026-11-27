package com.odel.identity.service;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.RequestInfo;
import com.odel.common.Tokens;
import com.odel.common.audit.AuditService;
import com.odel.common.internal.InternalClient;
import com.odel.common.security.Role;
import com.odel.common.security.SessionCookies;
import com.odel.common.security.SessionData;
import com.odel.common.security.SessionStore;
import com.odel.common.tenancy.TenantContext;
import com.odel.identity.RoleAssignment;
import com.odel.identity.RoleAssignmentRepository;
import com.odel.identity.SessionView;
import com.odel.identity.SignInService;
import com.odel.identity.TenantInfo;
import com.odel.identity.client.StudentServiceClient;
import com.odel.identity.dto.StudentAuthDtos.CentreView;
import com.odel.identity.dto.StudentAuthDtos.CodeSent;
import com.odel.identity.dto.StudentAuthDtos.StudentBrief;
import com.odel.identity.dto.StudentAuthDtos.StudentContact;
import com.odel.identity.dto.StudentAuthDtos.Verified;
import com.odel.identity.User;
import com.odel.identity.UserRepository;
import com.odel.identity.repository.StudentUserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.odel.identity.service.StudentCodeStore.Purpose;

/**
 * Students get in (D1 flows A and B): activation by matric number and an emailed code, a password, the privacy notice;
 * sign-in by matric number (or email) and password with a 15-minute pause after five wrong tries; reset by emailed code.
 * Students never use Google or two-step codes. Every step is audited.
 */
@Service
public class StudentAuthService {
    public static final String PRIVACY_VERSION = "2026-11";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);

    private final UserRepository users;
    private final StudentUserRepository byId;
    private final RoleAssignmentRepository roles;
    private final StudentCodeStore store;
    private final StudentServiceClient students;
    private final TenantInfo.Client tenants;
    private final InternalClient internal;
    private final SessionStore sessions;
    private final SessionCookies cookies;
    private final SignInService signIn;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final OdelProperties props;
    private final Clock clock;

    public StudentAuthService(UserRepository users, StudentUserRepository byId, RoleAssignmentRepository roles, StudentCodeStore store, StudentServiceClient students,
                              TenantInfo.Client tenants, InternalClient internal, SessionStore sessions, SessionCookies cookies,
                              SignInService signIn, AuditService audit, TransactionTemplate tx, OdelProperties props, Clock clock) {
        this.users = users;
        this.byId = byId;
        this.roles = roles;
        this.store = store;
        this.students = students;
        this.tenants = tenants;
        this.internal = internal;
        this.sessions = sessions;
        this.cookies = cookies;
        this.signIn = signIn;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- codes

    /** M-02 / M-09: find the student and email a code (or reuse the one sent less than a minute ago). */
    public CodeSent start(Purpose purpose, String login, boolean codeAlreadySent) {
        TenantInfo tenant = university();
        User user = tx.execute(s -> findStudent(login).orElse(null));
        if (user == null) {
            audit(tenant.id(), null, login, purpose == Purpose.ACTIVATION ? "STUDENT_ACTIVATION_FAILED" : "STUDENT_PASSWORD_RESET_FAILED",
                    "No student with " + clean(login) + " (" + (purpose == Purpose.ACTIVATION ? "activation" : "password reset") + ")", Map.of("reason", "not_found"));
            if (purpose == Purpose.ACTIVATION) {
                throw new ApiException(HttpStatus.NOT_FOUND, "matric_not_found",
                        "We could not find this number. Check it on your admission letter, or contact your study centre.");
            }
            throw notFound();
        }
        if (user.getStatus() == User.Status.SUSPENDED) throw suspended(tenant);
        boolean active = user.getPasswordHash() != null;
        if (purpose == Purpose.ACTIVATION && active) {
            throw new ApiException(HttpStatus.CONFLICT, "already_active",
                    "This account is already active. Sign in with your password, or reset it if you have forgotten it.");
        }
        if (purpose == Purpose.RESET && !active) {
            throw new ApiException(HttpStatus.CONFLICT, "not_activated", "This account is not active yet. Activate it first.");
        }
        Optional<StudentContact> contact = students.contact(user.getId());
        CentreView centre = contact.map(StudentContact::centre).filter(c -> c != null && c.name() != null).orElse(null);
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            String where = centre == null ? "your study centre" : centre.name();
            Map<String, Object> extra = new HashMap<>();
            extra.put("centre", centre);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "no_email",
                    "We have no email address for you, so we cannot send a code. The ODeL office at " + where + " can add it.", null, extra);
        }
        String flowId = store.flowFor(purpose, tenant.id(), user.getId());
        Instant now = clock.instant();
        Optional<CodePolicy.Stored> live = store.code(purpose, user.getId()).filter(c -> now.isBefore(c.expiresAt()) && c.wrongTries() < CodePolicy.MAX_TRIES);
        CodePolicy.SendDecision decision = CodePolicy.canSend(store.sends(user.getId(), now), lastSent(purpose, user.getId()), now);
        if (live.isPresent() && (codeAlreadySent || decision.verdict() == CodePolicy.SendVerdict.TOO_SOON)) {
            return sentView(flowId, user, live.get(), false, decision, centre, contact, now);
        }
        refuseIfLimited(decision);
        CodePolicy.Stored stored = send(purpose, user, tenant, contact, now, false);
        return sentView(flowId, user, stored, true, CodePolicy.canSend(store.sends(user.getId(), now), lastSent(purpose, user.getId()), now), centre, contact, now);
    }

    /** "Resend code". */
    public CodeSent resend(Purpose purpose, String flowId) {
        TenantInfo tenant = university();
        StudentCodeStore.Flow flow = flow(purpose, flowId, tenant);
        User user = tx.execute(s -> byId.findInTenant(flow.userId()).orElse(null));
        if (user == null || user.getStatus() == User.Status.SUSPENDED || user.getEmail() == null) throw expired();
        Instant now = clock.instant();
        CodePolicy.SendDecision decision = CodePolicy.canSend(store.sends(user.getId(), now), lastSent(purpose, user.getId()), now);
        if (decision.verdict() == CodePolicy.SendVerdict.TOO_SOON) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "resend_too_soon",
                    "Wait " + decision.retryInSeconds() + " seconds before asking for another code.", null, Map.of("retryInSeconds", decision.retryInSeconds()));
        }
        refuseIfLimited(decision);
        Optional<StudentContact> contact = students.contact(user.getId());
        CodePolicy.Stored stored = send(purpose, user, tenant, contact, now, false);
        CentreView centre = contact.map(StudentContact::centre).filter(c -> c != null && c.name() != null).orElse(null);
        return sentView(flowId, user, stored, true, CodePolicy.canSend(store.sends(user.getId(), now), lastSent(purpose, user.getId()), now), centre, contact, now);
    }

    /** M-03 / M-03R: the typed code. */
    public Verified verify(Purpose purpose, String flowId, String code) {
        TenantInfo tenant = university();
        StudentCodeStore.Flow flow = flow(purpose, flowId, tenant);
        User user = tx.execute(s -> byId.findInTenant(flow.userId()).orElse(null));
        if (user == null) throw expired();
        if (user.getStatus() == User.Status.SUSPENDED) throw suspended(tenant);
        Instant now = clock.instant();
        Optional<CodePolicy.Stored> stored = store.code(purpose, user.getId());
        CodePolicy.Check check = CodePolicy.check(stored.orElse(null), StudentCodeStore.hash(user.getId(), code), now);
        switch (check.verdict()) {
            case OK -> {
                store.deleteCode(purpose, user.getId());
                String token = store.newToken(new StudentCodeStore.Verified(tenant.id(), user.getId(), purpose, null));
                return new Verified(token, StudentCodeStore.FLOW_TTL.toSeconds(), brief(user));
            }
            case WRONG -> {
                CodePolicy.Stored s = stored.get();
                store.putCode(purpose, user.getId(), new CodePolicy.Stored(s.hash(), s.sentAt(), s.expiresAt(), s.wrongTries() + 1));
                audit(tenant.id(), user, null, "STUDENT_CODE_FAILED", user.getFullName() + " typed a wrong code", Map.of("attemptsLeft", check.attemptsLeft()));
                String tries = check.attemptsLeft() == 1 ? "1 try" : check.attemptsLeft() + " tries";
                throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_code", "That code is not right. " + tries + " left before we send a new one.",
                        null, Map.of("attemptsLeft", check.attemptsLeft()));
            }
            case USED_UP -> {
                store.deleteCode(purpose, user.getId());
                audit(tenant.id(), user, null, "STUDENT_CODE_FAILED", user.getFullName() + " typed a wrong code three times", Map.of("attemptsLeft", 0));
                CodePolicy.SendDecision decision = CodePolicy.canSend(store.sends(user.getId(), now), lastSent(purpose, user.getId()), now);
                if (decision.allowed() && user.getEmail() != null) {
                    send(purpose, user, tenant, students.contact(user.getId()), now, false);
                    throw new ApiException(HttpStatus.BAD_REQUEST, "new_code_sent", "That code was wrong three times, so we sent you a new one.",
                            null, Map.of("maskedEmail", CodePolicy.mask(user.getEmail()), "resendInSeconds", CodePolicy.RESEND_AFTER.toSeconds(),
                                    "expiresInSeconds", CodePolicy.VALID_FOR.toSeconds()));
                }
                throw codeExpired();
            }
            default -> throw codeExpired();
        }
    }

    // ---------------------------------------------------------------- activation

    /** M-04: the password is checked and held with the verified token until the privacy notice is accepted. */
    public void activationPassword(String token, String password, String confirm) {
        StudentCodeStore.Verified v = verified(token, Purpose.ACTIVATION);
        Map<String, String> problems = PasswordRules.check(password, confirm);
        if (!problems.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "weak_password", problems.values().iterator().next(), problems);
        store.saveToken(token, new StudentCodeStore.Verified(v.tenantId(), v.userId(), v.purpose(), BCRYPT.encode(password)));
    }

    /** M-05: consent recorded, account active, student signed in. */
    public SessionView completeActivation(String token, boolean accepted, String version, boolean keep, HttpServletResponse response) {
        StudentCodeStore.Verified v = verified(token, Purpose.ACTIVATION);
        if (!accepted) throw ApiException.fields(HttpStatus.BAD_REQUEST, "consent_required", "Tick the box to accept the privacy notice.",
                Map.of("acceptPrivacyNotice", "Tick the box to accept the privacy notice."));
        if (v.passwordHash() == null) throw new ApiException(HttpStatus.CONFLICT, "password_missing", "Create your password first.");
        String consentVersion = version == null || version.isBlank() ? PRIVACY_VERSION : version.trim();
        SessionView view = tx.execute(s -> {
            User user = byId.findInTenant(v.userId()).orElseThrow(this::expired);
            if (user.getStatus() == User.Status.SUSPENDED) throw suspended(tenants.get(v.tenantId()));
            if (user.getPasswordHash() != null) throw new ApiException(HttpStatus.CONFLICT, "already_active",
                    "This account is already active. Sign in with your password, or reset it if you have forgotten it.");
            Instant now = clock.instant();
            user.setPasswordHash(v.passwordHash());
            user.setPasswordChangedAt(now);
            user.setStatus(User.Status.ACTIVE);
            user.setAcceptedAt(now);
            user.setConsentVersion(consentVersion);
            user.setConsentAt(now);
            user.setFailedSignIns(0);
            user.setLockedUntil(null);
            user.setUpdatedAt(now);
            users.save(user);
            audit(v.tenantId(), user, null, "STUDENT_ACTIVATED", user.getFullName() + " (" + user.getMatricNumber() + ") activated the account", null);
            audit(v.tenantId(), user, null, "STUDENT_ACTIVATED_CONSENT", user.getFullName() + " accepted the privacy notice (version " + consentVersion + ")",
                    Map.of("version", consentVersion, "at", now.toString()));
            students.activated(user.getId(), now, consentVersion, now);
            return openSession(user, keep, response, "matric number and emailed code");
        });
        store.deleteToken(token);
        store.endFlow(Purpose.ACTIVATION, v.userId());
        return view;
    }

    // ---------------------------------------------------------------- reset

    /** M-11 "Save and sign in": every other session of the student ends. */
    public SessionView completeReset(String token, String password, String confirm, boolean keep, HttpServletResponse response) {
        StudentCodeStore.Verified v = verified(token, Purpose.RESET);
        Map<String, String> problems = PasswordRules.check(password, confirm);
        if (!problems.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "weak_password", problems.values().iterator().next(), problems);
        String hash = BCRYPT.encode(password);
        SessionView view = tx.execute(s -> {
            User user = byId.findInTenant(v.userId()).orElseThrow(this::expired);
            if (user.getStatus() == User.Status.SUSPENDED) throw suspended(tenants.get(v.tenantId()));
            Instant now = clock.instant();
            user.setPasswordHash(hash);
            user.setPasswordChangedAt(now);
            user.setFailedSignIns(0);
            user.setLockedUntil(null);
            user.setUpdatedAt(now);
            users.save(user);
            sessions.deleteAllForUser(user.getId());
            audit(v.tenantId(), user, null, "STUDENT_PASSWORD_RESET", user.getFullName() + " chose a new password with an emailed code; other sessions ended", null);
            return openSession(user, keep, response, "new password");
        });
        store.deleteToken(token);
        store.endFlow(Purpose.RESET, v.userId());
        return view;
    }

    /** W-22b "Send her a reset code by email": a code plus a link to the reset screen. */
    public void officeReset(UUID userId) {
        TenantInfo tenant = tenants.get(TenantContext.require());
        User user = tx.execute(s -> byId.findInTenant(userId).filter(User::isStudent).orElseThrow(ApiException::notFound));
        if (user.getPasswordHash() == null) throw new ApiException(HttpStatus.CONFLICT, "not_activated",
                "This student has not activated the account yet. Resend the activation message instead.");
        if (user.getEmail() == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "no_email",
                "This student has no email on record. Add one first.");
        Instant now = clock.instant();
        refuseIfLimited(CodePolicy.canSend(store.sends(user.getId(), now), lastSent(Purpose.RESET, user.getId()), now));
        send(Purpose.RESET, user, tenant, students.contact(user.getId()), now, true);
    }

    // ---------------------------------------------------------------- sign in

    private record Outcome(SessionView view, ApiException error) {}

    /** M-08. */
    public SessionView signIn(String login, String password, boolean keep, HttpServletResponse response) {
        TenantInfo tenant = university();
        Outcome out = tx.execute(s -> {
            User user = findStudent(login).orElse(null);
            if (user == null) {
                audit(tenant.id(), null, login, "STUDENT_SIGN_IN_FAILED", "Student sign-in refused for " + clean(login) + " (not_found)", Map.of("reason", "not_found"));
                return new Outcome(null, notFound());
            }
            if (user.getStatus() == User.Status.SUSPENDED) {
                audit(tenant.id(), user, null, "STUDENT_SIGN_IN_FAILED", user.getFullName() + " could not sign in (suspended)", Map.of("reason", "suspended"));
                return new Outcome(null, suspended(tenant));
            }
            if (user.getPasswordHash() == null) {
                return new Outcome(null, new ApiException(HttpStatus.CONFLICT, "not_activated",
                        "You have not activated your account yet. Choose First time here? Activate your account."));
            }
            Instant now = clock.instant();
            LockoutPolicy.State state = LockoutPolicy.current(user.getFailedSignIns(), user.getLockedUntil(), now);
            if (state.locked(now)) return new Outcome(null, locked(state, now));
            if (password == null || !BCRYPT.matches(password, user.getPasswordHash())) {
                LockoutPolicy.State next = LockoutPolicy.afterWrong(state, now);
                user.setFailedSignIns(next.failed());
                user.setLockedUntil(next.lockedUntil());
                users.save(user);
                audit(tenant.id(), user, null, "STUDENT_SIGN_IN_FAILED", user.getFullName() + " typed a wrong password", Map.of("reason", "wrong_password", "failed", next.failed()));
                if (next.locked(now)) {
                    audit(tenant.id(), user, null, "STUDENT_SIGN_IN_LOCKED", user.getFullName() + ": sign-in paused for 15 minutes after five wrong passwords",
                            Map.of("lockedUntil", next.lockedUntil().toString()));
                    return new Outcome(null, locked(next, now));
                }
                String tries = next.attemptsLeft() == 1 ? "1 try" : next.attemptsLeft() + " tries";
                return new Outcome(null, new ApiException(HttpStatus.UNAUTHORIZED, "wrong_password",
                        "Wrong password. " + tries + " left before sign-in pauses for 15 minutes.", null, Map.of("attemptsLeft", next.attemptsLeft())));
            }
            user.setFailedSignIns(0);
            user.setLockedUntil(null);
            return new Outcome(openSession(user, keep, response, "matric number and password"), null);
        });
        if (out.error() != null) throw out.error();
        return out.view();
    }

    // ---------------------------------------------------------------- helpers

    private SessionView openSession(User user, boolean keep, HttpServletResponse response, String method) {
        RoleAssignment role = roles.findByUserIdOrderByCreatedAt(user.getId()).stream().filter(r -> r.getRole() == Role.STUDENT).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "no_role", "Your account has no student role. Contact your study centre."));
        SessionData data = new SessionData();
        data.userId = user.getId();
        data.tenantId = user.getTenantId();
        data.stage = SessionData.Stage.SIGNED_IN;
        data.activeRoleId = role.getId();
        data.activeRole = Role.STUDENT;
        data.ip = RequestInfo.ip();
        data.userAgent = RequestInfo.userAgent();
        String token = sessions.create(data);
        if (keep) {
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SessionCookies.SESSION, token).httpOnly(true)
                    .secure(props.session().secureCookies()).sameSite("Lax").path("/").maxAge(props.session().absoluteTimeout()).build().toString());
        } else {
            cookies.setSession(response, token);
        }
        Instant now = clock.instant();
        user.setLastSignInAt(now);
        user.setUpdatedAt(now);
        users.save(user);
        audit(user.getTenantId(), user, null, "STUDENT_SIGN_IN", user.getFullName() + " signed in (" + method + ")", null);
        return signIn.view(Tokens.sha256(token), data);
    }

    private CodePolicy.Stored send(Purpose purpose, User user, TenantInfo tenant, Optional<StudentContact> contact, Instant now, boolean byOffice) {
        String code = String.format("%06d", Tokens.secureRandom().nextInt(1_000_000));
        CodePolicy.Stored stored = new CodePolicy.Stored(StudentCodeStore.hash(user.getId(), code), now, now.plus(CodePolicy.VALID_FOR), 0);
        store.putCode(purpose, user.getId(), stored);
        store.recordSend(user.getId(), now);
        String firstName = contact.map(StudentContact::firstName).filter(f -> f != null && !f.isBlank()).orElse(user.firstName());
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", firstName);
        vars.put("code", code);
        vars.put("minutes", CodePolicy.VALID_FOR.toMinutes());
        String key = purpose == Purpose.ACTIVATION ? "student.activation_code" : "student.password_reset_code";
        if (byOffice) {
            key = "student.password_reset_by_office";
            String portal = String.valueOf(internal.get(InternalClient.Service.TENANT, "/internal/tenants/" + tenant.id(), Map.class).get("portalUrl"));
            vars.put("resetLink", portal + "/reset-password?login=" + java.net.URLEncoder.encode(user.getMatricNumber(), java.nio.charset.StandardCharsets.UTF_8) + "&sent=1");
        }
        Map<String, Object> look = new HashMap<>();
        look.put("name", tenant.name());
        look.put("shortName", tenant.shortName());
        look.put("primaryColor", tenant.primaryColor() == null ? "#1A2368" : tenant.primaryColor());
        Map<String, Object> msg = new HashMap<>();
        msg.put("userId", user.getId());
        msg.put("email", user.getEmail());
        msg.put("templateKey", key);
        msg.put("vars", vars);
        msg.put("tenant", look);
        internal.post(InternalClient.Service.NOTIFICATION, "/internal/messages", msg, Map.class);
        String action = purpose == Purpose.ACTIVATION ? "STUDENT_ACTIVATION_CODE_SENT" : "STUDENT_PASSWORD_RESET_CODE_SENT";
        String summary = (purpose == Purpose.ACTIVATION ? "Activation code" : "Password reset code") + " emailed to " + user.getFullName()
                + " (" + CodePolicy.mask(user.getEmail()) + ")" + (byOffice ? " by the ODeL office" : "");
        tx.executeWithoutResult(s -> audit(tenant.id(), user, null, action, summary, null));
        return stored;
    }

    private CodeSent sentView(String flowId, User user, CodePolicy.Stored stored, boolean sent, CodePolicy.SendDecision after,
                              CentreView centre, Optional<StudentContact> contact, Instant now) {
        long resendIn = Math.max(0, CodePolicy.RESEND_AFTER.toSeconds() - java.time.Duration.between(stored.sentAt(), now).toSeconds());
        long expiresIn = Math.max(0, java.time.Duration.between(now, stored.expiresAt()).toSeconds());
        String first = contact.map(StudentContact::firstName).filter(f -> f != null && !f.isBlank()).orElse(user.firstName());
        return new CodeSent(flowId, CodePolicy.mask(user.getEmail()), sent, expiresIn, resendIn, Math.max(0, after.sendsLeftAfter() + (after.allowed() ? 1 : 0)),
                new StudentBrief(first, user.getFullName(), user.getMatricNumber()), centre);
    }

    private Instant lastSent(Purpose purpose, UUID userId) {
        return store.code(purpose, userId).map(CodePolicy.Stored::sentAt).orElse(null);
    }

    private void refuseIfLimited(CodePolicy.SendDecision d) {
        if (d.verdict() == CodePolicy.SendVerdict.HOURLY_LIMIT) {
            long minutes = Math.max(1, (d.retryInSeconds() + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_codes", "You have asked for 5 codes in the last hour. Try again in "
                    + minutes + (minutes == 1 ? " minute" : " minutes") + ", or contact your study centre.", null, Map.of("retryInSeconds", d.retryInSeconds()));
        }
        if (d.verdict() == CodePolicy.SendVerdict.TOO_SOON) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "resend_too_soon",
                    "Wait " + d.retryInSeconds() + " seconds before asking for another code.", null, Map.of("retryInSeconds", d.retryInSeconds()));
        }
    }

    private StudentCodeStore.Flow flow(Purpose purpose, String flowId, TenantInfo tenant) {
        return store.flow(flowId).filter(f -> f.purpose() == purpose && f.tenantId().equals(tenant.id())).orElseThrow(this::expired);
    }

    private StudentCodeStore.Verified verified(String token, Purpose purpose) {
        university();
        return store.token(token).filter(v -> v.purpose() == purpose && v.tenantId().equals(TenantContext.current())).orElseThrow(this::expired);
    }

    /** A student account found by matric number (any case, spaces ignored) or by email; staff accounts are never found. */
    public Optional<User> findStudent(String login) {
        if (login == null || login.isBlank()) return Optional.empty();
        String l = login.trim();
        Optional<User> u = l.contains("@") ? users.findByEmail(l) : users.findByMatricNumber(normalizeMatric(l));
        return u.filter(User::isStudent);
    }

    public static String normalizeMatric(String matric) {
        return matric == null ? null : matric.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private TenantInfo university() {
        UUID id = TenantContext.current();
        if (TenantContext.NONE.equals(id)) throw new ApiException(HttpStatus.NOT_FOUND, "unknown_host", "We could not find this university's portal.");
        TenantInfo t = tenants.get(id);
        if (t.isPlatform()) throw new ApiException(HttpStatus.NOT_FOUND, "unknown_host", "Students sign in on their university's portal.");
        if ("SUSPENDED".equals(t.status())) throw new ApiException(HttpStatus.FORBIDDEN, "university_suspended",
                t.name() + "'s portal is paused. Contact the ODeL office.");
        return t;
    }

    private void audit(UUID tenantId, User user, String login, String action, String summary, Object newValue) {
        AuditService.Actor actor = user == null ? new AuditService.Actor(null, null, clean(login), null)
                : new AuditService.Actor(user.getId(), user.getTenantId(), user.getFullName(), Role.STUDENT.name());
        audit.record(new AuditService.Entry(tenantId, actor, action, "user", user == null ? null : user.getId(), summary, null, newValue));
    }

    private static String clean(String login) {
        if (login == null) return "";
        String l = login.trim();
        return l.length() > 80 ? l.substring(0, 80) : l;
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "We could not find that matric number or email. Check it, or activate your account first.");
    }

    private ApiException suspended(TenantInfo tenant) {
        return new ApiException(HttpStatus.FORBIDDEN, "suspended", "Your account is suspended. Please contact the ODeL office at "
                + tenant.name() + " to restore access. Your work is safe.");
    }

    private ApiException locked(LockoutPolicy.State s, Instant now) {
        return new ApiException(HttpStatus.LOCKED, "locked", "Too many attempts. Sign-in is paused for 15 minutes. You can reset your password now instead.",
                null, Map.of("retryAt", s.lockedUntil().toString(), "retryInSeconds", s.retryInSeconds(now)));
    }

    private ApiException expired() {
        return new ApiException(HttpStatus.GONE, "expired", "This took too long. Enter your matric number again.");
    }

    private ApiException codeExpired() {
        return new ApiException(HttpStatus.GONE, "code_expired", "That code has expired. Send a new one.");
    }

    private StudentBrief brief(User user) {
        return new StudentBrief(user.firstName(), user.getFullName(), user.getMatricNumber());
    }
}
