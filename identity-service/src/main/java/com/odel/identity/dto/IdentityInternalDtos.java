package com.odel.identity.dto;

import com.odel.common.security.Role;
import com.odel.common.security.ScopeType;
import com.odel.identity.entity.User;
import java.time.Instant;
import java.util.UUID;

/** What identity-service takes and answers on its B0 internal endpoints (/internal/users). */
public final class IdentityInternalDtos {
    private IdentityInternalDtos() {}

    public record NewStaff(String fullName, String email, Role role, ScopeType scopeType, String scopeLabel, UUID createdBy) {}

    public record StaffView(UUID id, String fullName, String email, Instant invitedAt, boolean accepted, Instant acceptedAt) {
        public static StaffView of(User u) {
            return new StaffView(u.getId(), u.getFullName(), u.getEmail(), u.getInvitedAt(), u.getAcceptedAt() != null, u.getAcceptedAt());
        }
    }
}
