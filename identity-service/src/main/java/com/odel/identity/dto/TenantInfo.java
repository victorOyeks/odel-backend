package com.odel.identity.dto;

import com.odel.common.security.StaffSignIn;

import java.util.UUID;

/** What identity-service needs to know about a university, from tenant-service (read by {@link com.odel.identity.client.TenantInfoClient}). */
public record TenantInfo(UUID id, String kind, String slug, String name, String shortName, String staffEmailDomain, String staffSignIn,
                         String status, String primaryColor, String accentColor, String logoUrl) {
    public boolean isPlatform() { return "PLATFORM".equals(kind); }

    /** The provider console always signs in with Google; a university uses the method the provider chose. */
    public StaffSignIn method() {
        return isPlatform() ? StaffSignIn.GOOGLE : StaffSignIn.orGoogle(staffSignIn);
    }
}
