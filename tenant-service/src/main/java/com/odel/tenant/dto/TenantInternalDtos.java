package com.odel.tenant.dto;

import com.odel.tenant.entity.Tenant;
import java.util.UUID;

/** What tenant-service answers other services on /internal. */
public final class TenantInternalDtos {
    private TenantInternalDtos() {}

    public record CertResult(String domain, boolean ok, String error) {}

    public record HostView(UUID tenantId, String kind) {}

    public record TenantView(UUID id, String kind, String slug, String name, String shortName, String googleDomain,
                             String status, String primaryColor, String accentColor, String portalUrl, String logoUrl) {
        public static TenantView of(Tenant t, String portalUrl) {
            return new TenantView(t.getId(), t.getKind().name(), t.getSlug(), t.getName(), t.getShortName(),
                    t.getGoogleDomain(), t.getStatus().name(), t.getPrimaryColor(), t.getAccentColor(), portalUrl,
                    com.odel.tenant.controller.PublicContextController.logoPath(t));
        }
    }
}
