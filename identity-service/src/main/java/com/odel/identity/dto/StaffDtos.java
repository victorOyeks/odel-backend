package com.odel.identity.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What identity-service answers other services about staff (/internal/staff). */
public final class StaffDtos {
    private StaffDtos() {}

    public record StaffRole(String role, String scopeType, UUID scopeId, String scopeLabel) {}

    public record StaffMember(UUID id, String fullName, String email, String status, List<StaffRole> roles) {}

    public record StaffSummary(long total, long invited, long active, long suspended, Map<String, Long> byRole) {}

    /** From academic-service. */
    public record Scope(String scopeType, UUID id, String label) {}

    /** From tenant-service. */
    /** staffSignInHow: "Sign in with your Microsoft work account (your @wesleyuni.edu.ng address)". */
    public record TenantLook(UUID id, String name, String shortName, String staffEmailDomain, String staffSignIn, String staffSignInHow,
                             String primaryColor, String portalUrl) {
        public com.odel.common.security.StaffSignIn method() {
            return com.odel.common.security.StaffSignIn.orGoogle(staffSignIn);
        }

        public String signInHow() {
            return staffSignInHow != null ? staffSignInHow : method().how(staffEmailDomain);
        }
    }
}
