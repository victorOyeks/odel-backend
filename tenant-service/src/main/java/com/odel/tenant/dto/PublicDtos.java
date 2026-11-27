package com.odel.tenant.dto;

import java.util.UUID;

/** What the web app reads before sign-in (/api/v1/public). */
public final class PublicDtos {
    private PublicDtos() {}

    public record TenantBranding(UUID id, String slug, String name, String shortName, String primaryColor,
                                 String accentColor, String logoUrl, String googleDomain, String status) {}

    public record Context(String kind, TenantBranding tenant, boolean devSignIn) {}
}
