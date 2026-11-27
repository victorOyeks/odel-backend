package com.odel.identity.dto;

import java.util.List;
import java.util.UUID;

/** Sign-in request and response bodies (/api/v1/auth). */
public final class AuthDtos {
    private AuthDtos() {}

    public record CodeRequest(String code, Boolean trustDevice) {
        public boolean trust() { return Boolean.TRUE.equals(trustDevice); }
    }

    public record BackupVerified(SessionView session, Integer backupCodesLeft) {}

    public record Setup(String secret, String secretGrouped, String otpauthUri) {}

    public record Confirmed(List<String> backupCodes, SessionView session) {}

    public record Verified(SessionView session, Integer backupCodesLeft) {}

    public record ActiveRoleRequest(UUID roleAssignmentId) {}
}
