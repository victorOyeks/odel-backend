package com.odel.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The body of GET /session and of every step of sign-in. */
public record SessionView(String stage, UserView user, List<RoleView> roles, UUID activeRoleId,
                          long idleTimeoutSeconds, OffsetDateTime idleExpiresAt) {
    public record UserView(UUID id, String fullName, String email, String initials) {}

    public record RoleView(UUID id, String role, String roleLabel, String scopeType, UUID scopeId, String scopeLabel) {}
}
