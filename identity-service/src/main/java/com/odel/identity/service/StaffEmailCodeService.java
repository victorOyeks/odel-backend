package com.odel.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.ApiException;
import com.odel.common.Tokens;
import com.odel.common.audit.AuditService;
import com.odel.common.internal.InternalClient;
import com.odel.common.security.StaffSignIn;
import com.odel.common.tenancy.TenantContext;
import com.odel.identity.client.TenantInfoClient;
import com.odel.identity.dto.StaffCodeDtos.CodeSent;
import com.odel.identity.dto.TenantInfo;
import com.odel.identity.entity.User;
import com.odel.identity.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Staff sign-in by a code emailed to the work address, for universities whose staff use any email provider (Zoho, their
 * own mail server...). The code is the first step; the authenticator two-step follows exactly as after Google.
 *
 * <p>The same answer whether or not the address is a staff account: an address on the university's domain always gets a
 * flow, the same timings, the same limits and the same errors. Behind the scenes only a real, active staff account is
 * emailed a code; a suspended account or one without a role is emailed why it cannot sign in; an unknown address gets a
 * code nobody knows. Limits and timings are the students' ({@link CodePolicy}), kept per address.
 */
@Service
public class StaffEmailCodeService {
    private static final String FLOW = "odel:staffcode:flow:";
    private static final String FLOW_FOR = "odel:staffcode:flow-for:";
    private static final String CODE = "odel:staffcode:code:";
    private static final String SENDS = "odel:staffcode:sends:";
    static final Duration FLOW_TTL = Duration.ofMinutes(30);

    public record Flow(UUID tenantId, String email, String returnTo) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TenantInfoClient tenants;
    private final UserRepository users;
    private final SignInService signIn;
    private final InternalClient internal;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StaffEmailCodeService(StringRedisTemplate redis, ObjectMapper json, TenantInfoClient tenants, UserRepository users,
                                 SignInService signIn, InternalClient internal, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.tenants = tenants;
        this.users = users;
        this.signIn = signIn;
        this.internal = internal;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- steps

    /** The work email typed on the sign-in page. */
    public CodeSent start(String typed, String returnTo) {
        TenantInfo tenant = university();
        String email = StaffEmailRules.normalise(typed);
        if (!StaffEmailRules.looksLikeEmail(email)) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Enter your work email.",
                    Map.of("email", "Enter your work email, for example name@" + tenant.staffEmailDomain() + "."));
        }
        if (!StaffEmailRules.onDomain(email, tenant.staffEmailDomain())) {
            signIn.refused(tenant.id(), email, "wrong_domain", StaffSignIn.EMAIL_CODE);
            String msg = email + " is not a " + tenant.name() + " address. Use your @" + tenant.staffEmailDomain() + " work email.";
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "wrong_domain", msg, Map.of("email", msg));
        }
        String flowId = flowFor(tenant.id(), email, signIn.safeReturnTo(returnTo, tenant));
        Instant now = clock.instant();
        Optional<CodePolicy.Stored> live = code(tenant.id(), email).filter(c -> now.isBefore(c.expiresAt()) && c.wrongTries() < CodePolicy.MAX_TRIES);
        CodePolicy.SendDecision decision = decision(tenant.id(), email, now);
        if (live.isPresent() && decision.verdict() == CodePolicy.SendVerdict.TOO_SOON) {
            // Pressed twice: the code sent a moment ago still works.
            return view(flowId, email, live.get(), false, decision, now);
        }
        refuseIfLimited(decision);
        CodePolicy.Stored stored = issue(tenant, email, now);
        return view(flowId, email, stored, true, decision(tenant.id(), email, now), now);
    }

    /** "Send a new code". */
    public CodeSent resend(String flowId) {
        TenantInfo tenant = university();
        Flow flow = flow(flowId, tenant);
        Instant now = clock.instant();
        CodePolicy.SendDecision decision = decision(tenant.id(), flow.email(), now);
        refuseIfLimited(decision);
        CodePolicy.Stored stored = issue(tenant, flow.email(), now);
        return view(flowId, flow.email(), stored, true, decision(tenant.id(), flow.email(), now), now);
    }

    /** The typed code. A right one opens the half-way session (two-step next) and returns where the browser goes. */
    public String verify(String flowId, String typed, HttpServletRequest request, HttpServletResponse response) {
        TenantInfo tenant = university();
        Flow flow = flow(flowId, tenant);
        String email = flow.email();
        Instant now = clock.instant();
        Optional<CodePolicy.Stored> stored = code(tenant.id(), email);
        CodePolicy.Check check = CodePolicy.check(stored.orElse(null), hash(tenant.id(), email, typed), now);
        switch (check.verdict()) {
            case OK -> {
                redis.delete(CODE + key(tenant.id(), email));
                record Outcome(String next, String refused) {}
                Outcome out = tx.execute(s -> {
                    Optional<User> user = users.findByEmail(email);
                    Optional<String> refused = signIn.refusal(user);
                    if (refused.isPresent()) return new Outcome(null, refused.get());
                    return new Outcome(signIn.openStaffSession(user.get(), tenant.id(), flow.returnTo(), StaffSignIn.EMAIL_CODE, request, response), null);
                });
                endFlow(tenant.id(), email);
                if (out.refused() != null) {
                    // The person proved they hold the mailbox, so saying why is safe now.
                    signIn.refused(tenant.id(), email, out.refused(), StaffSignIn.EMAIL_CODE);
                    throw refusedError(out.refused());
                }
                return out.next();
            }
            case WRONG -> {
                CodePolicy.Stored s = stored.get();
                putCode(tenant.id(), email, new CodePolicy.Stored(s.hash(), s.sentAt(), s.expiresAt(), s.wrongTries() + 1));
                signIn.refused(tenant.id(), email, "wrong_code", StaffSignIn.EMAIL_CODE);
                String tries = check.attemptsLeft() == 1 ? "1 try" : check.attemptsLeft() + " tries";
                throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_code", "That code is not right. " + tries + " left before we send a new one.",
                        null, Map.of("attemptsLeft", check.attemptsLeft()));
            }
            case USED_UP -> {
                redis.delete(CODE + key(tenant.id(), email));
                signIn.refused(tenant.id(), email, "wrong_code_three_times", StaffSignIn.EMAIL_CODE);
                if (decision(tenant.id(), email, now).allowed()) {
                    issue(tenant, email, now);
                    throw new ApiException(HttpStatus.BAD_REQUEST, "new_code_sent",
                            "That code was wrong three times, so we sent a new one to " + email + ". Use the newest email.",
                            null, Map.of("email", email, "resendInSeconds", CodePolicy.RESEND_AFTER.toSeconds(),
                                    "expiresInSeconds", CodePolicy.VALID_FOR.toSeconds(), "attemptsLeft", CodePolicy.MAX_TRIES));
                }
                throw codeExpired();
            }
            default -> throw codeExpired();
        }
    }

    /** Dev only (DevController): makes the live code for this address expire now, to test the expired-code screen. */
    public boolean expireForTest(UUID tenantId, String email) {
        String e = StaffEmailRules.normalise(email);
        Optional<CodePolicy.Stored> c = code(tenantId, e);
        if (c.isEmpty()) return false;
        Instant past = clock.instant().minusSeconds(1);
        putCode(tenantId, e, new CodePolicy.Stored(c.get().hash(), c.get().sentAt().minus(CodePolicy.VALID_FOR), past, c.get().wrongTries()));
        return true;
    }

    // ---------------------------------------------------------------- sending

    /**
     * A new code for this address. Stored and counted the same way whoever the address belongs to, so nothing in the
     * answer or the limits tells an account from a stranger; only a real staff member's mailbox gets it.
     */
    private CodePolicy.Stored issue(TenantInfo tenant, String email, Instant now) {
        String code = String.format("%06d", Tokens.secureRandom().nextInt(1_000_000));
        CodePolicy.Stored stored = new CodePolicy.Stored(hash(tenant.id(), email, code), now, now.plus(CodePolicy.VALID_FOR), 0);
        putCode(tenant.id(), email, stored);
        recordSend(tenant.id(), email, now);
        record Who(User user, Optional<String> refused) {}
        Who who = tx.execute(s -> {
            Optional<User> u = users.findByEmail(email);
            return new Who(u.orElse(null), signIn.refusal(u));
        });
        if (who.refused().isEmpty()) {
            message(tenant, who.user(), "staff.sign_in_code", Map.of("fullName", who.user().getFullName(), "code", code,
                    "minutes", CodePolicy.VALID_FOR.toMinutes()));
            tx.executeWithoutResult(s -> audit.record(new AuditService.Entry(tenant.id(),
                    new AuditService.Actor(who.user().getId(), tenant.id(), who.user().getFullName(), null), "SIGN_IN_CODE_SENT", "user",
                    who.user().getId(), "Sign-in code emailed to " + who.user().getFullName() + " (" + CodePolicy.mask(email) + ")", null, null)));
        } else {
            String reason = who.refused().get();
            if (who.user() != null && Set.of("suspended", "no_role").contains(reason)) {
                // Tell the mailbox's owner why, instead of leaving them waiting for a code that never comes.
                boolean suspended = reason.equals("suspended");
                message(tenant, who.user(), "staff.sign_in_blocked", Map.of("fullName", who.user().getFullName(),
                        "reason", suspended ? "your staff account is suspended." : "your staff account has no role in the portal yet.",
                        "nextStep", suspended ? "Contact the ODeL office if you think this is a mistake."
                                : "Ask the ODeL office to give you a role, then sign in again."));
            }
            tx.executeWithoutResult(s -> signIn.refused(tenant.id(), email, reason, StaffSignIn.EMAIL_CODE));
        }
        return stored;
    }

    private void message(TenantInfo tenant, User user, String template, Map<String, Object> vars) {
        Map<String, Object> look = new HashMap<>();
        look.put("name", tenant.name());
        look.put("shortName", tenant.shortName());
        look.put("primaryColor", tenant.primaryColor() == null ? "#1A2368" : tenant.primaryColor());
        Map<String, Object> msg = new HashMap<>();
        msg.put("email", user.getEmail());
        msg.put("templateKey", template);
        msg.put("vars", vars);
        msg.put("tenant", look);
        internal.post(InternalClient.Service.NOTIFICATION, "/internal/messages", msg, Map.class);
    }

    // ---------------------------------------------------------------- rules and views

    private CodePolicy.SendDecision decision(UUID tenantId, String email, Instant now) {
        return CodePolicy.canSend(sends(tenantId, email, now), code(tenantId, email).map(CodePolicy.Stored::sentAt).orElse(null), now);
    }

    private static void refuseIfLimited(CodePolicy.SendDecision d) {
        if (d.verdict() == CodePolicy.SendVerdict.HOURLY_LIMIT) {
            long minutes = Math.max(1, (d.retryInSeconds() + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_codes", "You have asked for 5 codes in the last hour. Try again in "
                    + minutes + (minutes == 1 ? " minute" : " minutes") + ", or contact the ODeL office.", null, Map.of("retryInSeconds", d.retryInSeconds()));
        }
        if (d.verdict() == CodePolicy.SendVerdict.TOO_SOON) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "resend_too_soon",
                    "Wait " + d.retryInSeconds() + " seconds before asking for another code.", null, Map.of("retryInSeconds", d.retryInSeconds()));
        }
    }

    private static CodeSent view(String flowId, String email, CodePolicy.Stored stored, boolean sent, CodePolicy.SendDecision after, Instant now) {
        long resendIn = Math.max(0, CodePolicy.RESEND_AFTER.toSeconds() - Duration.between(stored.sentAt(), now).toSeconds());
        long expiresIn = Math.max(0, Duration.between(now, stored.expiresAt()).toSeconds());
        return new CodeSent(flowId, email, sent, expiresIn, resendIn, Math.max(0, after.sendsLeftAfter() + (after.allowed() ? 1 : 0)));
    }

    private TenantInfo university() {
        UUID id = TenantContext.current();
        if (TenantContext.NONE.equals(id)) throw new ApiException(HttpStatus.NOT_FOUND, "unknown_host", "We could not find this university's portal.");
        TenantInfo t = tenants.fresh(id);
        if (t.isPlatform()) throw new ApiException(HttpStatus.CONFLICT, "wrong_method", "The provider console signs in with Google.");
        if ("SUSPENDED".equals(t.status())) throw new ApiException(HttpStatus.FORBIDDEN, "university_suspended",
                t.name() + "'s portal is paused, so nobody can sign in at the moment. Contact the ODeL office for help.");
        if (t.method() != StaffSignIn.EMAIL_CODE) throw new ApiException(HttpStatus.CONFLICT, "wrong_method",
                t.name() + " staff sign in with " + t.method().label() + ". Reload the sign-in page to continue.");
        return t;
    }

    private static ApiException refusedError(String reason) {
        return switch (reason) {
            case "suspended" -> new ApiException(HttpStatus.FORBIDDEN, "suspended",
                    "Your staff account is suspended. Contact the ODeL office if you think this is a mistake.");
            case "no_role" -> new ApiException(HttpStatus.FORBIDDEN, "no_role",
                    "Your staff record is there, but no role has been given to you yet. Ask the ODeL office to give you a role, then sign in again.");
            default -> new ApiException(HttpStatus.FORBIDDEN, "not_linked",
                    "This email is not on the staff list. Ask the ODeL office to add you to the portal, then sign in again.");
        };
    }

    private static ApiException codeExpired() {
        return new ApiException(HttpStatus.GONE, "code_expired", "That code has expired. Send a new one.");
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.GONE, "expired", "This took too long. Enter your work email again.");
    }

    // ---------------------------------------------------------------- Redis

    static String hash(UUID tenantId, String email, String code) {
        return Tokens.sha256(tenantId + ":" + email + ":" + (code == null ? "" : code.replaceAll("\\s", "")));
    }

    private static String key(UUID tenantId, String email) {
        return tenantId + ":" + Tokens.sha256(email);
    }

    private String flowFor(UUID tenantId, String email, String returnTo) {
        String k = FLOW_FOR + key(tenantId, email);
        String existing = redis.opsForValue().get(k);
        if (existing != null && Boolean.TRUE.equals(redis.hasKey(FLOW + existing))) {
            write(FLOW + existing, new Flow(tenantId, email, returnTo), FLOW_TTL);
            redis.expire(k, FLOW_TTL);
            return existing;
        }
        String id = Tokens.random(24);
        write(FLOW + id, new Flow(tenantId, email, returnTo), FLOW_TTL);
        redis.opsForValue().set(k, id, FLOW_TTL);
        return id;
    }

    private Flow flow(String flowId, TenantInfo tenant) {
        if (flowId == null || flowId.isBlank()) throw expired();
        return read(FLOW + flowId, Flow.class).filter(f -> f.tenantId().equals(tenant.id())).orElseThrow(StaffEmailCodeService::expired);
    }

    private void endFlow(UUID tenantId, String email) {
        String id = redis.opsForValue().getAndDelete(FLOW_FOR + key(tenantId, email));
        if (id != null) redis.delete(FLOW + id);
    }

    private Optional<CodePolicy.Stored> code(UUID tenantId, String email) {
        return read(CODE + key(tenantId, email), CodePolicy.Stored.class);
    }

    private void putCode(UUID tenantId, String email, CodePolicy.Stored stored) {
        Duration ttl = Duration.between(clock.instant(), stored.expiresAt()).plusMinutes(1);
        write(CODE + key(tenantId, email), stored, ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(1) : ttl);
    }

    private List<Instant> sends(UUID tenantId, String email, Instant now) {
        String k = SENDS + key(tenantId, email);
        redis.opsForZSet().removeRangeByScore(k, 0, now.minus(CodePolicy.WINDOW).toEpochMilli());
        Set<String> all = redis.opsForZSet().range(k, 0, -1);
        return all == null ? List.of() : all.stream().map(s -> Instant.ofEpochMilli(Long.parseLong(s.split(":")[0]))).toList();
    }

    private void recordSend(UUID tenantId, String email, Instant now) {
        String k = SENDS + key(tenantId, email);
        redis.opsForZSet().add(k, now.toEpochMilli() + ":" + Tokens.random(4), now.toEpochMilli());
        redis.expire(k, CodePolicy.WINDOW.plusMinutes(5));
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(json.readValue(raw, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
