package com.odel.identity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Student accounts as student-service manages them (/internal/students/**). */
public final class StudentAccountDtos {
    private StudentAccountDtos() {}

    public record Account(UUID studentId, String matricNumber, String fullName, String email, String phone) {}

    public record AccountsRequest(List<Account> accounts) {}

    public record Created(UUID studentId, UUID userId, boolean created) {}

    public record CreatedResponse(List<Created> accounts) {}

    public record EmailEntry(String matricNumber, String email) {}

    public record EmailCheckRequest(List<EmailEntry> entries) {}

    public record ConflictsResponse(List<EmailEntry> conflicts) {}

    public record AccountView(UUID userId, String status, Instant activatedAt, Instant lastSignInAt, Instant lockedUntil,
                              String consentVersion, Instant consentAt) {}

    public record ChangeRequest(String matricNumber, String fullName, String email, String phone) {}
}
