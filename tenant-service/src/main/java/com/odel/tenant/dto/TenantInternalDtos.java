package com.odel.tenant.dto;

import com.odel.tenant.entity.Tenant;
import java.util.UUID;

/** What tenant-service answers other services on /internal. */
public final class TenantInternalDtos {
    private TenantInternalDtos() {}

    public record CertResult(String domain, boolean ok, String error) {}

    public record HostView(UUID tenantId, String kind) {}

    /**
     * staffSignInHow: the sentence staff emails use ("Sign in with your Microsoft work account (your @wesleyuni.edu.ng
     * address)"). googleDomain is the old name of staffEmailDomain, kept so services built before the rename still read it.
     */
    public record TenantView(UUID id, String kind, String slug, String name, String shortName, String staffEmailDomain,
                             String staffSignIn, String staffSignInHow, String googleDomain,
                             String status, String primaryColor, String accentColor, String portalUrl, String logoUrl) {
        public static TenantView of(Tenant t, String portalUrl) {
            var method = t.getStaffSignIn() == null ? com.odel.common.security.StaffSignIn.GOOGLE : t.getStaffSignIn();
            return new TenantView(t.getId(), t.getKind().name(), t.getSlug(), t.getName(), t.getShortName(),
                    t.getStaffEmailDomain(), method.name(), method.how(t.getStaffEmailDomain()), t.getStaffEmailDomain(),
                    t.getStatus().name(), t.getPrimaryColor(), t.getAccentColor(), portalUrl,
                    com.odel.tenant.controller.PublicContextController.logoPath(t));
        }
    }
}
