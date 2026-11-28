package com.odel.identity.service;

import com.odel.common.ApiException;
import com.odel.common.Tokens;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.security.SessionStore;
import com.odel.common.tenancy.TenantContext;
import com.odel.identity.client.StudentMessagesClient;
import com.odel.identity.client.StudentServiceClient;
import com.odel.identity.client.TenantInfoClient;
import com.odel.identity.dto.StudentSecurityDtos.*;
import com.odel.identity.dto.TenantInfo;
import com.odel.identity.entity.MfaBackupCode;
import com.odel.identity.entity.User;
import com.odel.identity.repository.MfaBackupCodeRepository;
import com.odel.identity.repository.StudentUserRepository;
import com.odel.identity.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.odel.identity.service.StudentCodeStore.Purpose;

/**
 * The student's own account from the profile page (B3, L-14d … L-14j): change the sign-in email with a code sent to the new
 * address, change the password, recovery codes, signed-in devices. Every change is audited and the student is emailed.
 * The password confirmations count with sign-in's wrong passwords (five pause the account for 15 minutes).
 */
@Service
public class StudentAccountService {
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);
    public static final int RECOVERY_CODES = 8;
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private final UserRepository users;
    private final StudentUserRepository byId;
    private final MfaBackupCodeRepository codes;
    private final StudentCodeStore store;
    private final StudentServiceClient students;
    private final StudentMessagesClient messages;
    private final TenantInfoClient tenants;
    private final SessionStore sessions;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final java.time.Clock clock;

    public StudentAccountService(UserRepository users, StudentUserRepository byId, MfaBackupCodeRepository codes, StudentCodeStore store,
                                 StudentServiceClient students, StudentMessagesClient messages, TenantInfoClient tenants, SessionStore sessions,
                                 AuditService audit, TransactionTemplate tx, java.time.Clock clock) {
        this.users = users;
        this.byId = byId;
        this.codes = codes;
        this.store = store;
        this.students = students;
        this.messages = messages;
        this.tenants = tenants;
        this.sessions = sessions;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the security card

    public Security security(CurrentUser cu) {
        User u = me(cu);
        RecoveryCodes rc = recoveryCodes(cu);
        Sessions s = sessions(cu);
        return new Security(u.getPasswordChangedAt(), rc.total() == 0 ? null : rc, new SessionCount(s.items().size(), s.summary()));
    }

    // ---------------------------------------------------------------- password check (phone change, email change, codes)

    /**
     * Checks the student's password. A wrong one counts towards the 15-minute pause like a wrong sign-in; a right one clears the count.
     * The count is saved before the error is thrown (the error is built inside the transaction and thrown after it commits).
     */
    public void checkPassword(UUID userId, String password, String field) {
        ApiException error = tx.execute(s -> {
            User u = byId.findInTenant(userId).filter(User::isStudent).orElseThrow(ApiException::notFound);
            Instant now = clock.instant();
            LockoutPolicy.State state = LockoutPolicy.current(u.getFailedSignIns(), u.getLockedUntil(), now);
            if (state.locked(now)) return locked(state, now);
            if (password == null || password.isEmpty()) {
                return ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Enter your password to confirm it is you.",
                        Map.of(field, "Enter your password to confirm it is you."));
            }
            if (u.getPasswordHash() != null && BCRYPT.matches(password, u.getPasswordHash())) {
                if (u.getFailedSignIns() != 0 || u.getLockedUntil() != null) {
                    u.setFailedSignIns(0);
                    u.setLockedUntil(null);
                    users.save(u);
                }
                return null;
            }
            LockoutPolicy.State next = LockoutPolicy.afterWrong(state, now);
            u.setFailedSignIns(next.failed());
            u.setLockedUntil(next.lockedUntil());
            users.save(u);
            record(u, "STUDENT_PASSWORD_CHECK_FAILED", u.getFullName() + " typed a wrong password on the profile page", Map.of("failed", next.failed()));
            if (next.locked(now)) return locked(next, now);
            String tries = next.attemptsLeft() == 1 ? "1 try" : next.attemptsLeft() + " tries";
            String msg = "That password is not right. " + tries + " left before your account pauses for 15 minutes.";
            return new ApiException(HttpStatus.UNAUTHORIZED, "wrong_password", msg, Map.of(field, msg), Map.of("attemptsLeft", next.attemptsLeft()));
        });
        if (error != null) throw error;
    }

    // ---------------------------------------------------------------- email change (L-14d, L-14e)

    public EmailCodeSent startEmailChange(CurrentUser cu, String newEmail, String password) {
        User u = me(cu);
        TenantInfo tenant = tenants.get(u.getTenantId());
        String email = newEmail == null ? "" : newEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > 320 || !EMAIL.matcher(email).matches()) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Enter an email address, like adeola@gmail.com.",
                    Map.of("newEmail", "Enter an email address, like adeola@gmail.com."));
        }
        if (email.equalsIgnoreCase(u.getEmail() == null ? "" : u.getEmail())) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "same_email", "That is already your email.", Map.of("newEmail", "That is already your email."));
        }
        refuseIfTaken(email, u.getId());
        checkPassword(u.getId(), password, "password");

        Instant now = clock.instant();
        Optional<String> pending = store.newEmail(u.getId());
        if (pending.isPresent() && !pending.get().equals(email)) {
            store.deleteCode(Purpose.EMAIL_CHANGE, u.getId());
            store.endFlow(Purpose.EMAIL_CHANGE, u.getId());
        }
        String flowId = store.flowFor(Purpose.EMAIL_CHANGE, u.getTenantId(), u.getId());
        store.putNewEmail(u.getId(), email);
        Optional<CodePolicy.Stored> live = store.code(Purpose.EMAIL_CHANGE, u.getId()).filter(c -> now.isBefore(c.expiresAt()) && c.wrongTries() < CodePolicy.MAX_TRIES);
        CodePolicy.SendDecision decision = CodePolicy.canSend(store.sends(u.getId(), now), lastSent(u.getId()), now);
        if (live.isPresent() && pending.map(email::equals).orElse(false) && decision.verdict() == CodePolicy.SendVerdict.TOO_SOON) {
            return sentView(flowId, email, live.get(), false, decision, now);
        }
        refuseIfLimited(decision);
        CodePolicy.Stored stored = sendCode(u, tenant, email, now);
        return sentView(flowId, email, stored, true, CodePolicy.canSend(store.sends(u.getId(), now), lastSent(u.getId()), now), now);
    }

    public EmailCodeSent resendEmailChange(CurrentUser cu, String changeId) {
        User u = me(cu);
        flow(changeId, u);
        String email = store.newEmail(u.getId()).orElseThrow(StudentAccountService::expired);
        Instant now = clock.instant();
        CodePolicy.SendDecision decision = CodePolicy.canSend(store.sends(u.getId(), now), lastSent(u.getId()), now);
        refuseIfLimited(decision);
        CodePolicy.Stored stored = sendCode(u, tenants.get(u.getTenantId()), email, now);
        return sentView(changeId, email, stored, true, CodePolicy.canSend(store.sends(u.getId(), now), lastSent(u.getId()), now), now);
    }

    public EmailChanged verifyEmailChange(CurrentUser cu, String changeId, String code) {
        User me = me(cu);
        flow(changeId, me);
        String email = store.newEmail(me.getId()).orElseThrow(StudentAccountService::expired);
        TenantInfo tenant = tenants.get(me.getTenantId());
        Instant now = clock.instant();
        Optional<CodePolicy.Stored> stored = store.code(Purpose.EMAIL_CHANGE, me.getId());
        CodePolicy.Check check = CodePolicy.check(stored.orElse(null), StudentCodeStore.hash(me.getId(), code), now);
        switch (check.verdict()) {
            case OK -> { }
            case WRONG -> {
                CodePolicy.Stored s = stored.get();
                store.putCode(Purpose.EMAIL_CHANGE, me.getId(), new CodePolicy.Stored(s.hash(), s.sentAt(), s.expiresAt(), s.wrongTries() + 1));
                tx.executeWithoutResult(t -> record(me, "STUDENT_CODE_FAILED", me.getFullName() + " typed a wrong email-change code", Map.of("attemptsLeft", check.attemptsLeft())));
                String tries = check.attemptsLeft() == 1 ? "1 try" : check.attemptsLeft() + " tries";
                throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_code", "That code is not right. " + tries + " left before we send a new one.",
                        null, Map.of("attemptsLeft", check.attemptsLeft()));
            }
            case USED_UP -> {
                store.deleteCode(Purpose.EMAIL_CHANGE, me.getId());
                CodePolicy.SendDecision decision = CodePolicy.canSend(store.sends(me.getId(), now), lastSent(me.getId()), now);
                if (decision.allowed()) {
                    sendCode(me, tenant, email, now);
                    throw new ApiException(HttpStatus.BAD_REQUEST, "new_code_sent", "That code was wrong three times, so we sent you a new one.",
                            null, Map.of("maskedEmail", CodePolicy.mask(email), "resendInSeconds", CodePolicy.RESEND_AFTER.toSeconds(),
                                    "expiresInSeconds", CodePolicy.VALID_FOR.toSeconds()));
                }
                throw codeExpired();
            }
            default -> throw codeExpired();
        }
        record Done(String oldEmail, User user) {}
        Done done = tx.execute(t -> {
            User u = byId.findInTenant(me.getId()).orElseThrow(StudentAccountService::expired);
            refuseIfTaken(email, u.getId());
            String old = u.getEmail();
            u.setEmail(email);
            u.setUpdatedAt(now);
            users.save(u);
            audit.record(new AuditService.Entry(u.getTenantId(), actor(u), "STUDENT_EMAIL_CHANGED", "user", u.getId(),
                    u.getFullName() + " changed the sign-in email to " + CodePolicy.mask(email) + " (code sent to the new address)",
                    old == null ? null : Map.of("email", old), Map.of("email", email)));
            students.emailChanged(u.getId(), email, old);   // fails → the change is rolled back and she tries again
            return new Done(old, u);
        });
        store.deleteCode(Purpose.EMAIL_CHANGE, me.getId());
        store.endFlow(Purpose.EMAIL_CHANGE, me.getId());
        store.deleteNewEmail(me.getId());

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", me.firstName());
        vars.put("newEmail", CodePolicy.mask(email));
        vars.put("oldEmail", CodePolicy.mask(done.oldEmail()));
        vars.put("when", ProfileFormat.when(now));
        if (done.oldEmail() != null) messages.notice(tenant, null, done.oldEmail(), "student.email_changed_old", vars, null);
        messages.notice(tenant, me.getId(), email, "student.email_changed", vars, "/profile");
        return new EmailChanged(email, CodePolicy.mask(email), now);
    }

    // ---------------------------------------------------------------- password (L-14f)

    public void changePassword(CurrentUser cu, String current, String next, String confirm) {
        User me = me(cu);
        Map<String, String> problems = new LinkedHashMap<>();
        PasswordRules.check(next, confirm).forEach((k, v) -> problems.put(k.equals("password") ? "newPassword" : k, v));
        if (!problems.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "weak_password", problems.values().iterator().next(), problems);
        checkPassword(me.getId(), current, "currentPassword");
        if (BCRYPT.matches(next, me.getPasswordHash())) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "same_password", "Your new password must be different from the current one.",
                    Map.of("newPassword", "Your new password must be different from the current one."));
        }
        String hash = BCRYPT.encode(next);
        Instant now = clock.instant();
        int ended = tx.execute(t -> {
            User u = byId.findInTenant(me.getId()).orElseThrow(ApiException::notFound);
            u.setPasswordHash(hash);
            u.setPasswordChangedAt(now);
            u.setFailedSignIns(0);
            u.setLockedUntil(null);
            u.setUpdatedAt(now);
            users.save(u);
            int n = sessions.deleteAllForUserExcept(u.getId(), cu.sessionKey());
            record(u, "STUDENT_PASSWORD_CHANGED", u.getFullName() + " changed the password on the profile page" + (n > 0 ? "; " + n + " other session(s) ended" : ""), null);
            return n;
        });
        if (me.getEmail() != null) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", me.firstName());
            vars.put("when", ProfileFormat.when(now));
            vars.put("signedOut", ended == 0 ? "" : ended == 1 ? "We signed you out of 1 other device." : "We signed you out of " + ended + " other devices.");
            messages.notice(tenants.get(me.getTenantId()), me.getId(), me.getEmail(), "student.password_changed", vars, "/profile");
        }
    }

    // ---------------------------------------------------------------- recovery codes (L-14g)

    public RecoveryCodes recoveryCodes(CurrentUser cu) {
        User u = me(cu);
        List<MfaBackupCode> all = tx.execute(t -> codes.findByUserId(u.getId()));
        long left = all.stream().filter(c -> c.getUsedAt() == null).count();
        Instant made = all.stream().map(MfaBackupCode::getCreatedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new RecoveryCodes(all.size(), (int) left, made, null);
    }

    public RecoveryCodes makeRecoveryCodes(CurrentUser cu, String password) {
        User me = me(cu);
        checkPassword(me.getId(), password, "password");
        Instant now = clock.instant();
        List<String> out = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String digits = String.format("%08d", Tokens.secureRandom().nextInt(100_000_000));
            out.add(digits.substring(0, 4) + "-" + digits.substring(4));
            hashes.add(BCRYPT.encode(digits));
        }
        tx.executeWithoutResult(t -> {
            codes.deleteByUserId(me.getId());
            for (String h : hashes) {
                MfaBackupCode c = new MfaBackupCode();
                c.setId(UUID.randomUUID());
                c.setUserId(me.getId());
                c.setCodeHash(h);
                c.setCreatedAt(now);
                codes.save(c);
            }
            record(me, "STUDENT_RECOVERY_CODES_MADE", me.getFullName() + " made " + RECOVERY_CODES + " new recovery codes (the old ones stopped working)", null);
        });
        if (me.getEmail() != null) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", me.firstName());
            vars.put("when", ProfileFormat.when(now));
            messages.notice(tenants.get(me.getTenantId()), me.getId(), me.getEmail(), "student.recovery_codes_made", vars, "/profile");
        }
        return new RecoveryCodes(RECOVERY_CODES, RECOVERY_CODES, now, out);
    }

    // ---------------------------------------------------------------- signed-in devices (L-14j)

    public Sessions sessions(CurrentUser cu) {
        requireSignedIn(cu);
        List<SessionItem> items = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (SessionStore.Found f : sessions.list(cu.userId())) {
            boolean current = f.key().equals(cu.sessionKey());
            String device = ProfileFormat.device(f.data().userAgent);
            items.add(new SessionItem(publicId(f.key()), device, ProfileFormat.maskIp(f.data().ip), f.data().createdAt, current));
            if (!current) others.add(device);
        }
        items.sort(Comparator.comparing(SessionItem::current).reversed()
                .thenComparing(SessionItem::signedInAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Sessions(items, ProfileFormat.summary(others));
    }

    public int signOutOthers(CurrentUser cu) {
        requireSignedIn(cu);
        int n = sessions.deleteAllForUserExcept(cu.userId(), cu.sessionKey());
        tx.executeWithoutResult(t -> users.findInTenant(cu.userId()).ifPresent(u -> audit.record(new AuditService.Entry(cu.tenantId(), actor(u, cu),
                "SIGN_OUT_OTHERS", "user", u.getId(), u.getFullName() + " signed out of " + n + " other device(s)", null, null))));
        return n;
    }

    public void signOutOne(CurrentUser cu, String id) {
        requireSignedIn(cu);
        SessionStore.Found found = sessions.list(cu.userId()).stream().filter(f -> publicId(f.key()).equals(id)).findFirst()
                .filter(f -> !f.key().equals(cu.sessionKey())).orElseThrow(ApiException::notFound);
        String device = ProfileFormat.device(found.data().userAgent);
        sessions.delete(found.key(), cu.userId());
        tx.executeWithoutResult(t -> users.findInTenant(cu.userId()).ifPresent(u -> audit.record(new AuditService.Entry(cu.tenantId(), actor(u, cu),
                "SIGN_OUT_DEVICE", "user", u.getId(), u.getFullName() + " signed out " + device, null, null))));
    }

    /** The session's id on the screen: a hash of the Redis key, so the key itself never leaves the server. */
    static String publicId(String key) {
        return Tokens.sha256("odel-session-id:" + key).substring(0, 20);
    }

    // ---------------------------------------------------------------- helpers

    private User me(CurrentUser cu) {
        if (cu.activeRole() != Role.STUDENT) throw ApiException.forbidden();
        return tx.execute(t -> byId.findInTenant(cu.userId()).filter(User::isStudent).orElseThrow(ApiException::forbidden));
    }

    private static void requireSignedIn(CurrentUser cu) {
        if (cu == null || cu.userId() == null) throw ApiException.forbidden();
    }

    private void refuseIfTaken(String email, UUID self) {
        Boolean taken = tx.execute(t -> users.findByEmail(email).filter(o -> !o.getId().equals(self)).isPresent());
        if (Boolean.TRUE.equals(taken)) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", "This email is already used by another account. Use a different one.",
                    Map.of("newEmail", "This email is already used by another account. Use a different one."), null);
        }
    }

    private StudentCodeStore.Flow flow(String changeId, User u) {
        return store.flow(changeId).filter(f -> f.purpose() == Purpose.EMAIL_CHANGE && f.userId().equals(u.getId())
                && f.tenantId().equals(TenantContext.current())).orElseThrow(StudentAccountService::expired);
    }

    private CodePolicy.Stored sendCode(User u, TenantInfo tenant, String email, Instant now) {
        String code = String.format("%06d", Tokens.secureRandom().nextInt(1_000_000));
        CodePolicy.Stored stored = new CodePolicy.Stored(StudentCodeStore.hash(u.getId(), code), now, now.plus(CodePolicy.VALID_FOR), 0);
        store.putCode(Purpose.EMAIL_CHANGE, u.getId(), stored);
        store.recordSend(u.getId(), now);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", u.firstName());
        vars.put("code", code);
        vars.put("minutes", CodePolicy.VALID_FOR.toMinutes());
        messages.send(tenant, null, email, "student.email_change_code", vars, null);   // no bell: the code is for the new address only
        tx.executeWithoutResult(t -> record(u, "STUDENT_EMAIL_CHANGE_CODE_SENT", "Email-change code sent to " + CodePolicy.mask(email) + " for " + u.getFullName(), null));
        return stored;
    }

    private EmailCodeSent sentView(String flowId, String email, CodePolicy.Stored stored, boolean sent, CodePolicy.SendDecision after, Instant now) {
        long resendIn = Math.max(0, CodePolicy.RESEND_AFTER.toSeconds() - Duration.between(stored.sentAt(), now).toSeconds());
        long expiresIn = Math.max(0, Duration.between(now, stored.expiresAt()).toSeconds());
        return new EmailCodeSent(flowId, CodePolicy.mask(email), sent, expiresIn, resendIn, Math.max(0, after.sendsLeftAfter() + (after.allowed() ? 1 : 0)));
    }

    private Instant lastSent(UUID userId) {
        return store.code(Purpose.EMAIL_CHANGE, userId).map(CodePolicy.Stored::sentAt).orElse(null);
    }

    private static void refuseIfLimited(CodePolicy.SendDecision d) {
        if (d.verdict() == CodePolicy.SendVerdict.HOURLY_LIMIT) {
            long minutes = Math.max(1, (d.retryInSeconds() + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_codes", "You have asked for 5 codes in the last hour. Try again in "
                    + minutes + (minutes == 1 ? " minute" : " minutes") + ".", null, Map.of("retryInSeconds", d.retryInSeconds()));
        }
        if (d.verdict() == CodePolicy.SendVerdict.TOO_SOON) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "resend_too_soon",
                    "Wait " + d.retryInSeconds() + " seconds before asking for another code.", null, Map.of("retryInSeconds", d.retryInSeconds()));
        }
    }

    private void record(User u, String action, String summary, Object newValue) {
        audit.record(new AuditService.Entry(u.getTenantId(), actor(u), action, "user", u.getId(), summary, null, newValue));
    }

    private static AuditService.Actor actor(User u) {
        return new AuditService.Actor(u.getId(), u.getTenantId(), u.getFullName(), Role.STUDENT.name());
    }

    private static AuditService.Actor actor(User u, CurrentUser cu) {
        return new AuditService.Actor(u.getId(), u.getTenantId(), u.getFullName(), cu.activeRole() == null ? null : cu.activeRole().name());
    }

    private static ApiException locked(LockoutPolicy.State s, Instant now) {
        return new ApiException(HttpStatus.LOCKED, "locked", "Too many wrong passwords. Your account is paused for 15 minutes. Try again then, or reset your password.",
                null, Map.of("retryAt", s.lockedUntil().toString(), "retryInSeconds", s.retryInSeconds(now)));
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.GONE, "expired", "This took too long. Start again.");
    }

    private static ApiException codeExpired() {
        return new ApiException(HttpStatus.GONE, "code_expired", "That code has expired. Send a new one.");
    }
}
