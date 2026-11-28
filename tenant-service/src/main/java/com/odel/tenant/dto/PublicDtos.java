package com.odel.tenant.dto;

import java.util.UUID;

/** What the web app reads before sign-in (/api/v1/public). */
public final class PublicDtos {
    private PublicDtos() {}

    /**
     * staffEmailDomain: staff work emails end with it. staffSignIn: GOOGLE | MICROSOFT | EMAIL_CODE (the provider console is
     * always GOOGLE). googleDomain is the old name of staffEmailDomain, kept for older web builds.
     */
    public record TenantBranding(UUID id, String slug, String name, String shortName, String primaryColor,
                                 String accentColor, String logoUrl, String staffEmailDomain, String staffSignIn,
                                 String googleDomain, String status) {}

    public record Context(String kind, TenantBranding tenant, boolean devSignIn) {}
}
