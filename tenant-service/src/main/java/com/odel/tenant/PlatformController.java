package com.odel.tenant;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.audit.AuditService;
import com.odel.common.ApiException;
import com.odel.common.PageResponse;
import com.odel.common.files.StorageService;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.CurrentUserArg;
import com.odel.tenant.PublicContextController;
import com.odel.tenant.Tenant;
import com.odel.common.tenancy.TenantContext;
import com.odel.tenant.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.odel.common.internal.InternalClient;

/** Provider console (role SUPER_ADMIN on the console host): universities and the platform audit trail. */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {
    private static final long MAX_LOGO_BYTES = 2 * 1024 * 1024;

    private final TenantRepository tenants;
    private final TenantSetupService setup;
    private final StorageService storage;
    private final PublicContextController branding;
    private final JdbcTemplate jdbc;
    private final InternalClient internal;
    private final CustomDomainService domains;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PlatformController(TenantRepository tenants, TenantSetupService setup, StorageService storage,
                              PublicContextController branding, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, InternalClient internal,
                              @org.springframework.context.annotation.Lazy CustomDomainService domains) {
        this.domains = domains;
        this.internal = internal;
        this.tenants = tenants;
        this.setup = setup;
        this.storage = storage;
        this.branding = branding;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

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

    @GetMapping("/tenants")
    public Map<String, List<TenantRow>> list() {
        List<TenantRow> rows = tenants.findByKindOrderByCreatedAtDesc(Tenant.Kind.UNIVERSITY).stream()
                .map(t -> new TenantRow(t.getId(), t.getName(), t.getShortName(), t.getSlug(), t.getWebAddress(), t.getStatus().name(),
                        0, 0, t.getCreatedAt(), admin(t))).toList();
        return Map.of("items", rows);
    }

    @GetMapping("/tenants/address-available")
    public Map<String, TenantSetupService.Availability> available(@RequestParam(required = false) String webAddress,
                                                                  @RequestParam(required = false) String slug) {
        return setup.availability(webAddress, slug);
    }

    @PostMapping("/tenants")
    public ResponseEntity<TenantDetail> create(CurrentUserArg.Holder cu, @RequestBody TenantSetupService.NewUniversity body) {
        if (body.branding() != null && body.branding().logoKey() != null) {
            var size = storage.size(body.branding().logoKey());
            if (size.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The logo did not upload.", Map.of("logoKey", "Upload the logo again."));
            if (size.getAsLong() > MAX_LOGO_BYTES) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The logo is too large.", Map.of("logoKey", "Use a file under 2 MB."));
        }
        Tenant t = setup.create(body, actor(cu.user()));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail(t));
    }

    @GetMapping("/tenants/{id}")
    public TenantDetail get(@PathVariable UUID id) {
        return detail(tenants.findById(id).filter(t -> !t.isPlatform()).orElseThrow(ApiException::notFound));
    }

    @PatchMapping("/tenants/{id}")
    public TenantDetail update(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody TenantSetupService.Changes body) {
        if (body.branding() != null && body.branding().logoKey() != null && !body.branding().logoKey().isEmpty()) {
            var size = storage.size(body.branding().logoKey());
            if (size.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The logo did not upload.", Map.of("logoKey", "Upload the logo again."));
            if (size.getAsLong() > MAX_LOGO_BYTES) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The logo is too large.", Map.of("logoKey", "Use a file under 2 MB."));
        }
        return detail(setup.update(id, body, actor(cu.user())));
    }

    /** "Check now" on the university page: looks the own domain up straight away instead of waiting for the 5-minute check. */
    @PostMapping("/tenants/{id}/custom-domain/check")
    public TenantDetail checkDomain(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return detail(domains.check(id, actor(cu.user())));
    }

    @PostMapping("/tenants/{id}/resend-invitation")
    public ResponseEntity<Void> resend(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        setup.resendInvitation(id, actor(cu.user()));
        return ResponseEntity.noContent().build();
    }

    public record LogoUploadRequest(String fileName, String contentType, Long size) {}

    @PostMapping("/logo-uploads")
    public Map<String, Object> logoUpload(@RequestBody LogoUploadRequest body) {
        String ext = switch (body.contentType() == null ? "" : body.contentType()) {
            case "image/svg+xml" -> "svg";
            case "image/png" -> "png";
            default -> throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Use an SVG or PNG logo.", Map.of("file", "Use an SVG or PNG logo."));
        };
        if (body.size() == null || body.size() <= 0 || body.size() > MAX_LOGO_BYTES) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The logo must be under 2 MB.", Map.of("file", "Use a file under 2 MB."));
        }
        String key = "logos/" + UUID.randomUUID() + "." + ext;
        Duration ttl = Duration.ofMinutes(15);
        return Map.of("key", key, "uploadUrl", storage.presignPut(key, ttl), "method", "PUT",
                "headers", Map.of("Content-Type", body.contentType()), "expiresAt", clock.instant().plus(ttl));
    }

    @GetMapping("/logo-uploads/preview")
    public Map<String, String> logoPreview(@RequestParam String key) {
        if (!key.matches("logos/[a-f0-9-]{36}\\.(svg|png)")) throw ApiException.notFound();
        return Map.of("url", storage.presignGet(key, Duration.ofMinutes(30)));
    }

    public record AuditRow(UUID id, Instant at, Map<String, String> actor, String action, String summary,
                           Map<String, Object> tenant, String ip) {}

    @GetMapping("/audit")
    public PageResponse<AuditRow> audit(@RequestParam(required = false) UUID tenantId, @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        int s = Math.min(Math.max(size, 1), 200), p = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        String where = "";
        if (tenantId != null) { where = "where a.tenant_id = ?"; args.add(tenantId); }
        Long total = jdbc.queryForObject("select count(*) from app.audit_log a " + where, Long.class, args.toArray());
        args.add(s);
        args.add((long) p * s);
        List<AuditRow> rows = jdbc.query("""
                select a.id, a.created_at, a.actor_name, a.actor_role, a.action, a.summary, a.tenant_id, t.name, a.ip
                from app.audit_log a join tenants t on t.id = a.tenant_id %s order by a.created_at desc limit ? offset ?""".formatted(where),
                (rs, i) -> new AuditRow(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                        nullSafe("fullName", rs.getString(3), "role", rs.getString(4)), rs.getString(5), rs.getString(6),
                        Map.of("id", rs.getObject(7, UUID.class), "name", rs.getString(8)), rs.getString(9)),
                args.toArray());
        return new PageResponse<>(rows, p, s, total == null ? 0 : total);
    }

    private static Map<String, String> nullSafe(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (v1 != null) m.put(k1, v1);
        if (v2 != null) m.put(k2, v2);
        return m;
    }

    private TenantDetail detail(Tenant t) {
        AdminView admin = admin(t);
        String createdBy = t.getCreatedByName();
        var b = branding.branding(t);
        boolean readable = Contrast.ratio(t.getPrimaryColor(), "#FFFFFF") >= Contrast.MIN;
        String platform = setup.platformUrl(t);
        CustomDomain custom = t.getWebAddress() == null ? null
                : new CustomDomain(t.getWebAddress(), t.getWebAddressStatus().name(), "CNAME", platform.replaceFirst("^https?://", "").replaceFirst(":\\d+$", ""),
                        t.getWebAddressCheckedAt(), t.getWebAddressError(), t.getWebAddressLiveAt());
        List<NextStep> steps = new java.util.ArrayList<>();
        steps.add(new NextStep("University created with its own data, kept apart from every other university", true));
        steps.add(new NextStep("Live at " + platform, true));
        if (custom != null) {
            steps.add(switch (t.getWebAddressStatus()) {
                case LIVE -> new NextStep("Own domain live: https://" + t.getWebAddress(), true);
                case DNS_FOUND -> new NextStep(t.getWebAddress() + " points to the platform; issuing its certificate (a few minutes)", false);
                default -> new NextStep("Waiting for the university's IT team: a CNAME record for " + t.getWebAddress() + " pointing to " + custom.pointTo(), false);
            });
        }
        steps.add(admin == null ? new NextStep("First admin not invited yet", false)
                : admin.accepted() ? new NextStep(admin.fullName() + " accepted the invitation and signed in", true)
                : new NextStep("Invitation emailed to " + admin.fullName() + "; waiting for the first sign-in", false));
        steps.add(new NextStep("Waiting: " + (admin == null ? "the first admin" : admin.fullName()) + " completes the setup checklist", false));
        return new TenantDetail(t.getId(), t.getName(), t.getShortName(), t.getSlug(), t.getWebAddress(), setup.portalUrl(t),
                platform, custom, t.getGoogleDomain(), t.getStatus().name(), t.getCreatedAt(), createdBy == null ? null : Map.of("fullName", createdBy),
                new BrandingView(t.getPrimaryColor(), t.getAccentColor(), b.logoUrl(), readable), admin, steps);
    }

    private AdminView admin(Tenant t) {
        return setup.firstAdmin(t)
                .map(a -> new AdminView(a.fullName(), a.email(), a.invitedAt(), a.accepted(), a.acceptedAt()))
                .orElse(null);
    }

    private AuditService.Actor actor(CurrentUser cu) {
        String name = internal.get(InternalClient.Service.IDENTITY, "/internal/users/" + cu.userId(), Map.class).get("fullName").toString();
        return new AuditService.Actor(cu.userId(), cu.tenantId(), name, cu.activeRole() == null ? null : cu.activeRole().name());
    }
}
