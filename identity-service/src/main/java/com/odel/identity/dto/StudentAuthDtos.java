package com.odel.identity.dto;

import java.util.UUID;

/** Requests and responses of the students' activation, sign-in and password reset (/api/v1/auth/student/**). */
public final class StudentAuthDtos {
    private StudentAuthDtos() {}

    // ---- requests ----

    public record StartRequest(String login, Boolean codeAlreadySent) {}

    public record FlowRefRequest(String activationId, String resetId) {}

    public record VerifyCodeRequest(String activationId, String resetId, String code) {}

    public record PasswordRequest(String activationToken, String resetToken, String password, String confirmPassword, Boolean keepSignedIn) {}

    public record CompleteActivationRequest(String activationToken, Boolean acceptPrivacyNotice, String privacyNoticeVersion, Boolean keepSignedIn) {}

    public record SignInRequest(String login, String password, Boolean keepSignedIn) {}

    // ---- responses ----

    public record CentreView(String name, String phone) {}

    public record StudentBrief(String firstName, String fullName, String matricNumber) {}

    /** A code was sent (or the one sent less than a minute ago is still the one to type). */
    public record CodeSent(String flowId, String maskedEmail, boolean codeSent, long expiresInSeconds, long resendInSeconds,
                           int sendsLeftThisHour, StudentBrief student, CentreView centre) {}

    public record Verified(String token, long expiresInSeconds, StudentBrief student) {}

    /** What student-service tells identity about a student (GET /internal/students/contact). */
    public record StudentContact(UUID studentId, String firstName, CentreView centre) {}
}
