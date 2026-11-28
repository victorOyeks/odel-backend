package com.odel.identity.dto;

import java.time.Instant;
import java.util.List;

/** The student's own account on the profile page (B3, L-14d … L-14j): /api/v1/me/security, /me/email-change, /me/recovery-codes, /me/sessions. */
public final class StudentSecurityDtos {
    private StudentSecurityDtos() {}

    public record SessionCount(int count, String summary) {}

    public record Security(Instant passwordChangedAt, RecoveryCodes recoveryCodes, SessionCount sessions) {}

    public record RecoveryCodes(int total, int left, Instant createdAt, List<String> codes) {}

    public record SessionItem(String id, String device, String ip, Instant signedInAt, boolean current) {}

    public record Sessions(List<SessionItem> items, String summary) {}

    public record EmailCodeSent(String changeId, String maskedEmail, boolean codeSent, long expiresInSeconds, long resendInSeconds, int sendsLeftThisHour) {}

    public record EmailChanged(String email, String maskedEmail, Instant changedAt) {}

    public record EmailChangeRequest(String newEmail, String password) {}

    public record EmailChangeRef(String changeId) {}

    public record EmailChangeVerify(String changeId, String code) {}

    public record PasswordChange(String currentPassword, String newPassword, String confirmPassword) {}

    public record PasswordOnly(String password) {}

    public record SignedOut(int signedOut) {}

    public record RecoveryResetRequest(String login, String recoveryCode) {}
}
