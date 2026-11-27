package com.odel.platform;

import com.odel.audit.AuditService;
import com.odel.common.ApiException;
import com.odel.common.PageResponse;
import com.odel.files.StorageService;
import com.odel.identity.CurrentUser;
import com.odel.identity.CurrentUserArg;
import com.odel.identity.User;
import com.odel.identity.UserRepository;
import com.odel.tenancy.PublicContextController;
import com.odel.tenancy.Tenant;
import com.odel.tenancy.TenantContext;
import com.odel.tenancy.TenantRepository;
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

/** Provider console (role SUPER_ADMIN on the console host): universities and the platform audit trail. */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {
    private static final long MAX_LOGO_BYTES = 2 * 1024 * 1024;

    private final TenantRepository tenants;
    private final TenantSetupService setup;
    private final UserRepository users;
    private final StorageService storage;
    private final PublicContextController branding;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PlatformController(TenantRepository tenants, TenantSetupService setup, UserRepository users, StorageService storage,
                              PublicContextController branding, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.tenants = tenants;
        this.setup = setup;
        this.users = users;
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

    public record TenantDetail(UUID id, String name, String shortName, String slug, String webAddress, String portalUrl,
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
        Long total = jdbc.queryForObject("select count(*) from audit_log a " + where, Long.class, args.toArray());
        args.add(s);
        args.add((long) p * s);
        List<AuditRow> rows = jdbc.query("""
                select a.id, a.created_at, a.actor_name, a.actor_role, a.action, a.summary, a.tenant_id, t.name, a.ip
                from audit_log a join tenants t on t.id = a.tenant_id %s order by a.created_at desc limit ? offset ?""".formatted(where),
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
        String createdBy = t.getCreatedBy() == null ? null
                : TenantContext.runAs(TenantContext.PLATFORM_TENANT_ID, () -> tx.execute(s -> users.findById(t.getCreatedBy()).map(User::getFullName).orElse(null)));
        var b = branding.branding(t);
        boolean readable = Contrast.ratio(t.getPrimaryColor(), "#FFFFFF") >= Contrast.MIN;
        String address = t.getWebAddress() != null ? t.getWebAddress() : t.getSlug() + " address";
        List<NextStep> steps = List.of(
                new NextStep("Tenant created with its own data store", true),
                new NextStep("Branding applied to web, emails and slips", readable),
                new NextStep(admin == null ? "Invitation not sent" : "Invitation emailed to " + admin.fullName(), admin != null),
                new NextStep(t.getWebAddress() == null ? "Using the platform address " + t.getSlug() + "." + "<base domain>" : "Waiting: DNS record for " + address, false),
                new NextStep("Waiting: " + (admin == null ? "the first admin" : admin.fullName()) + " completes the setup checklist", false));
        return new TenantDetail(t.getId(), t.getName(), t.getShortName(), t.getSlug(), t.getWebAddress(), setup.portalUrl(t),
                t.getGoogleDomain(), t.getStatus().name(), t.getCreatedAt(), createdBy == null ? null : Map.of("fullName", createdBy),
                new BrandingView(t.getPrimaryColor(), t.getAccentColor(), b.logoUrl(), readable), admin, steps);
    }

    private AdminView admin(Tenant t) {
        return TenantContext.runAs(t.getId(), () -> tx.execute(s -> setup.firstAdmin(t)
                .map(u -> new AdminView(u.getFullName(), u.getEmail(), u.getInvitedAt(), u.getAcceptedAt() != null, u.getAcceptedAt()))
                .orElse(null)));
    }

    private AuditService.Actor actor(CurrentUser cu) {
        String name = users.findById(cu.userId()).map(User::getFullName).orElse("Platform admin");
        return new AuditService.Actor(cu.userId(), cu.tenantId(), name, cu.activeRole() == null ? null : cu.activeRole().name());
    }
}
