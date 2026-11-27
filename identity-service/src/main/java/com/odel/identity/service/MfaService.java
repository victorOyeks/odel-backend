package com.odel.identity.service;

import com.odel.identity.entity.TrustedDevice;

import com.odel.identity.entity.MfaBackupCode;

import com.odel.identity.entity.User;

import com.odel.identity.repository.UserRepository;
import com.odel.identity.repository.MfaBackupCodeRepository;
import com.odel.identity.repository.TrustedDeviceRepository;
import com.odel.identity.dto.SessionView;
import com.odel.identity.dto.TenantInfo;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.audit.AuditService;
import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.Tokens;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/** Two-step sign-in (W-03, W-04, W-05): authenticator codes, 8 one-time backup codes, trusted browsers. */
@Service
public class MfaService {
    public static final int MAX_ATTEMPTS = 3;
    private static final int BACKUP_CODES = 8;

    private final UserRepository users;
    private final MfaBackupCodeRepository backupCodes;
    private final TrustedDeviceRepository trusted;
    private final TenantInfo.Client tenants;
    private final SessionStore sessions;
    private final SessionCookies cookies;
    private final SignInService signIn;
    private final SecretBox box;
    private final AuditService audit;
    private final StringRedisTemplate redis;
    private final OdelProperties props;
    private final Clock clock;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(8);

    public MfaService(UserRepository users, MfaBackupCodeRepository backupCodes, TrustedDeviceRepository trusted,
                      TenantInfo.Client tenants, SessionStore sessions, SessionCookies cookies, SignInService signIn,
                      SecretBox box, AuditService audit, StringRedisTemplate redis, OdelProperties props, Clock clock) {
        this.users = users;
        this.backupCodes = backupCodes;
        this.trusted = trusted;
        this.tenants = tenants;
        this.sessions = sessions;
        this.cookies = cookies;
        this.signIn = signIn;
        this.box = box;
        this.audit = audit;
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    public record Setup(String secret, String secretGrouped, String otpauthUri) {}

    public record Confirmed(List<String> backupCodes, SessionView session) {}

    public record Verified(SessionView session, Integer backupCodesLeft) {}

    @Transactional
    public Setup setup(CurrentUser cu) {
        requireStage(cu, SessionData.Stage.MFA_SETUP);
        SessionData data = cu.data();
        if (data.pendingSecretEnc == null) {
            // Two set-up calls at once (a double render, a reload) must get the same secret: first writer wins.
            String key = "odel:mfa-pending:" + cu.sessionKey();
            redis.opsForValue().setIfAbsent(key, box.encrypt(Totp.newSecret(Tokens.secureRandom())), props.session().signInTimeout());
            data.pendingSecretEnc = redis.opsForValue().get(key);
            sessions.save(cu.sessionKey(), data);
        }
        String secret = box.decrypt(data.pendingSecretEnc);
        User user = users.findById(cu.userId()).orElseThrow();
        String issuer = tenants.get(cu.tenantId()).shortName();
        String label = enc(issuer) + ":" + enc(user.getEmail());
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer) + "&digits=6&period=30";
        return new Setup(secret, Totp.group(secret), uri);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Confirmed confirm(CurrentUser cu, String code, HttpServletResponse response) {
        requireStage(cu, SessionData.Stage.MFA_SETUP);
        SessionData data = cu.data();
        if (data.pendingSecretEnc == null) throw ApiException.badRequest("no_setup", "Start the set-up again.");
        User user = users.findById(cu.userId()).orElseThrow();
        String secret = box.decrypt(data.pendingSecretEnc);
        if (!checkTotp(user, secret, code)) throw wrong(cu, user);

        Instant now = clock.instant();
        user.setMfaSecretEnc(data.pendingSecretEnc);
        user.setMfaEnabledAt(now);
        backupCodes.deleteByUserId(user.getId());
        List<String> codes = newBackupCodes(user, now);
        data.pendingSecretEnc = null;
        data.mfaAttempts = 0;
        signIn.chooseRoleStage(user, data);
        audit.record(new AuditService.Entry(data.tenantId, signIn.actor(user, data), "MFA_SET_UP", "user", user.getId(),
                user.getFullName() + " set up two-step sign-in", null, null));
        signIn.signedIn(user, data, "Google and a new two-step code");
        sessions.save(cu.sessionKey(), data);
        return new Confirmed(codes, signIn.view(cu.sessionKey(), data));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Verified verify(CurrentUser cu, String code, boolean trustDevice, HttpServletResponse response) {
        requireStage(cu, SessionData.Stage.MFA_REQUIRED);
        User user = users.findById(cu.userId()).orElseThrow();
        if (!checkTotp(user, box.decrypt(user.getMfaSecretEnc()), code)) throw wrong(cu, user);
        return pass(cu, user, trustDevice, response, "Google and a two-step code", null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Verified backup(CurrentUser cu, String code, boolean trustDevice, HttpServletResponse response) {
        requireStage(cu, SessionData.Stage.MFA_REQUIRED);
        User user = users.findById(cu.userId()).orElseThrow();
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        List<MfaBackupCode> unused = backupCodes.findByUserIdAndUsedAtIsNull(user.getId());
        MfaBackupCode match = digits.length() != 8 ? null
                : unused.stream().filter(c -> bcrypt.matches(digits, c.getCodeHash())).findFirst().orElse(null);
        if (match == null) throw wrong(cu, user);
        match.setUsedAt(clock.instant());
        audit.record(new AuditService.Entry(cu.tenantId(), signIn.actor(user, cu.data()), "MFA_BACKUP_CODE_USED", "user",
                user.getId(), user.getFullName() + " used a backup code (" + (unused.size() - 1) + " left)", null, null));
        return pass(cu, user, trustDevice, response, "Google and a backup code", unused.size() - 1);
    }

    private Verified pass(CurrentUser cu, User user, boolean trustDevice, HttpServletResponse response, String method, Integer left) {
        SessionData data = cu.data();
        data.mfaAttempts = 0;
        if (trustDevice) {
            String token = Tokens.random(32);
            TrustedDevice d = new TrustedDevice();
            d.setId(UUID.randomUUID());
            d.setUserId(user.getId());
            d.setTokenHash(Tokens.sha256(token));
            d.setUserAgent(cu.data().userAgent);
            d.setCreatedAt(clock.instant());
            d.setExpiresAt(clock.instant().plus(Duration.ofDays(props.session().trustDeviceDays())));
            trusted.save(d);
            cookies.setTrust(response, token);
        }
        signIn.chooseRoleStage(user, data);
        signIn.signedIn(user, data, method);
        sessions.save(cu.sessionKey(), data);
        return new Verified(signIn.view(cu.sessionKey(), data), left);
    }

    private boolean checkTotp(User user, String secret, String code) {
        OptionalLong step = Totp.verify(secret, code == null ? null : code.replace(" ", ""), clock.instant());
        if (step.isEmpty()) return false;
        // A code works once: replaying the same code within its 90-second window is refused.
        Boolean fresh = redis.opsForValue().setIfAbsent("odel:totp-used:" + user.getId() + ":" + step.getAsLong(), "1", Duration.ofSeconds(120));
        return Boolean.TRUE.equals(fresh);
    }

    private ApiException wrong(CurrentUser cu, User user) {
        SessionData data = cu.data();
        data.mfaAttempts++;
        if (data.mfaAttempts >= MAX_ATTEMPTS) {
            sessions.delete(cu.sessionKey(), data.userId);
            audit.record(new AuditService.Entry(data.tenantId, signIn.actor(user, data), "MFA_FAILED", "user", user.getId(),
                    "Three wrong two-step codes for " + user.getFullName() + "; sign-in ended", null, Map.of("attempts", MAX_ATTEMPTS)));
            return new ApiException(HttpStatus.UNAUTHORIZED, "too_many_attempts",
                    "Three wrong codes. For your security, sign in again with Google.");
        }
        sessions.save(cu.sessionKey(), data);
        return new ApiException(HttpStatus.BAD_REQUEST, "wrong_code", "That code is not right. Check the app and try again.",
                null, Map.of("attemptsLeft", MAX_ATTEMPTS - data.mfaAttempts));
    }

    private List<String> newBackupCodes(User user, Instant now) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODES; i++) {
            String digits = String.format("%08d", Tokens.secureRandom().nextInt(100_000_000));
            MfaBackupCode c = new MfaBackupCode();
            c.setId(UUID.randomUUID());
            c.setUserId(user.getId());
            c.setCodeHash(bcrypt.encode(digits));
            c.setCreatedAt(now);
            backupCodes.save(c);
            out.add(digits.substring(0, 4) + "-" + digits.substring(4));
        }
        return out;
    }

    private static void requireStage(CurrentUser cu, SessionData.Stage stage) {
        if (cu.data().stage != stage) throw new ApiException(HttpStatus.CONFLICT, "wrong_stage", "This step is already done.");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
