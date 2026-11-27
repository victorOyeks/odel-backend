package com.odel.identity.dto;

import java.util.UUID;

/** What identity-service needs to know about a university, from tenant-service (read by {@link com.odel.identity.client.TenantInfoClient}). */
public record TenantInfo(UUID id, String kind, String slug, String name, String shortName, String googleDomain, String status,
                         String primaryColor, String accentColor) {
    public boolean isPlatform() { return "PLATFORM".equals(kind); }
}
