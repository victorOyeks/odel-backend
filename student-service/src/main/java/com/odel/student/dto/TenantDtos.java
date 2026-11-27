package com.odel.student.dto;

import java.util.UUID;

/** tenant-service's /internal/tenants/{id} and /internal/tenants/{id}/portal. */
public final class TenantDtos {
    private TenantDtos() {}

    public record TenantView(UUID id, String kind, String slug, String name, String shortName, String googleDomain, String status,
                             String primaryColor, String accentColor, String portalUrl) {}
}
