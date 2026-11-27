package com.odel.tenant.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Provider console bodies (/api/v1/platform). */
public final class PlatformDtos {
    private PlatformDtos() {}

    public record AdminView(String fullName, String email, Instant invitedAt, boolean accepted, Instant acceptedAt) {}

    public record TenantRow(UUID id, String name, String shortName, String slug, String webAddress, String status,
                            long students, long centres, Instant createdAt, AdminView firstAdmin) {}

    public record NextStep(String label, boolean done) {}

    public record BrandingView(String primaryColor, String accentColor, String logoUrl, boolean readabilityPassed) {}

    /** The university's own domain and what is left to do for it. pointTo = what its DNS record must point at. */
    public record CustomDomain(String address, String status, String recordType, String pointTo, Instant checkedAt,
                               String problem, Instant liveAt) {}

    public record TenantDetail(UUID id, String name, String shortName, String slug, String webAddress, String portalUrl,
                               String platformUrl, CustomDomain customDomain,
                               String googleDomain, String status, Instant createdAt, Map<String, String> createdBy,
                               BrandingView branding, AdminView firstAdmin, List<NextStep> nextSteps) {}

    public record LogoUploadRequest(String fileName, String contentType, Long size) {}

    public record AuditRow(UUID id, Instant at, Map<String, String> actor, String action, String summary,
                           Map<String, Object> tenant, String ip) {}

    public record Branding(String primaryColor, String accentColor, String logoKey) {}

    public record FirstAdmin(String fullName, String email) {}

    public record NewUniversity(String name, String shortName, String slug, String webAddress, String googleDomain,
                                Branding branding, FirstAdmin firstAdmin) {}

    public record Availability(boolean available, String message) {}

    /** webAddress: a new own domain (checked again from scratch), or "" to remove it. */
    public record Changes(String name, String shortName, String webAddress, Branding branding) {}

    /** The first admin as identity-service reports it. */
    public record Admin(UUID id, String fullName, String email, Instant invitedAt, boolean accepted, Instant acceptedAt) {}
}
