package com.odel.platform;

import com.odel.audit.AuditService;
import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.identity.Role;
import com.odel.identity.RoleAssignment;
import com.odel.identity.RoleAssignmentRepository;
import com.odel.identity.ScopeType;
import com.odel.identity.User;
import com.odel.identity.UserRepository;
import com.odel.messaging.MessageService;
import com.odel.tenancy.Tenant;
import com.odel.tenancy.TenantContext;
import com.odel.tenancy.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** The provider adds a university (D10 SA-02 to SA-05b, smallest form for B0): tenant, branding, first admin. */
@Service
public class TenantSetupService {
    public static final String SLUG = "^[a-z][a-z0-9-]{1,30}$";
    public static final String HOST = "^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$";
    private static final java.util.Set<String> RESERVED = java.util.Set.of("console", "platform", "www", "api", "admin", "app", "staging", "mail");

    private final TenantRepository tenants;
    private final UserRepository users;
    private final RoleAssignmentRepository roles;
    private final MessageService messages;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final OdelProperties props;
    private final Clock clock;

    public TenantSetupService(TenantRepository tenants, UserRepository users, RoleAssignmentRepository roles,
                              MessageService messages, AuditService audit, TransactionTemplate tx, OdelProperties props, Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.roles = roles;
        this.messages = messages;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public record Branding(String primaryColor, String accentColor, String logoKey) {}

    public record FirstAdmin(String fullName, String email) {}

    public record NewUniversity(String name, String shortName, String slug, String webAddress, String googleDomain,
                                Branding branding, FirstAdmin firstAdmin) {}

    public record Availability(boolean available, String message) {}

    public Map<String, Availability> availability(String webAddress, String slug) {
        Map<String, Availability> out = new LinkedHashMap<>();
        if (webAddress != null && !webAddress.isBlank()) {
            String w = webAddress.trim().toLowerCase(Locale.ROOT);
            out.put("webAddress", tenants.findByWebAddressIgnoreCase(w)
                    .map(t -> new Availability(false, w + " is already used by " + t.getName()))
                    .orElse(new Availability(true, null)));
        }
        if (slug != null && !slug.isBlank()) {
            String s = slug.trim().toLowerCase(Locale.ROOT);
            boolean taken = RESERVED.contains(s) || tenants.existsBySlug(s);
            out.put("slug", new Availability(!taken, taken ? "The short address " + s + " is already taken" : null));
        }
        return out;
    }

    /** Called on the console host; the actor is a platform admin. */
    public Tenant create(NewUniversity in, AuditService.Actor actor) {
        Map<String, String> fields = validate(in);
        if (!fields.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Some details need fixing.", fields);
        String primary = in.branding().primaryColor().toUpperCase(Locale.ROOT);
        String accent = in.branding().accentColor().toUpperCase(Locale.ROOT);
        double ratio = Contrast.ratio(primary, "#FFFFFF");
        if (ratio < Contrast.MIN) {
            throw ApiException.fields(HttpStatus.UNPROCESSABLE_ENTITY, "contrast", "The main colour is hard to read with white text on it.",
                    Map.of("primaryColor", "White text on " + primary + " is " + Contrast.format(ratio) + "; it needs 4.5 : 1"));
        }
        String slug = in.slug().trim().toLowerCase(Locale.ROOT);
        String web = in.webAddress() == null || in.webAddress().isBlank() ? null : in.webAddress().trim().toLowerCase(Locale.ROOT);
        if (RESERVED.contains(slug) || tenants.existsBySlug(slug)) {
            throw new ApiException(HttpStatus.CONFLICT, "slug_taken", "That short address is already taken.");
        }
        if (web != null && tenants.existsByWebAddressIgnoreCase(web)) {
            throw new ApiException(HttpStatus.CONFLICT, "web_address_taken", web + " is already used by another university.");
        }

        Instant now = clock.instant();
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setKind(Tenant.Kind.UNIVERSITY);
        t.setSlug(slug);
        t.setName(in.name().trim());
        t.setShortName(in.shortName().trim());
        t.setWebAddress(web);
        t.setGoogleDomain(in.googleDomain().trim().toLowerCase(Locale.ROOT));
        t.setStatus(Tenant.Status.SETTING_UP);
        t.setPrimaryColor(primary);
        t.setAccentColor(accent);
        t.setLogoKey(in.branding().logoKey());
        t.setCreatedAt(now);
        t.setCreatedBy(actor.userId());
        t.setUpdatedAt(now);

        return TenantContext.runAs(t.getId(), () -> tx.execute(s -> {
            tenants.saveAndFlush(t); // flushed: messages and audit below insert with plain JDBC
            User admin = new User();
            admin.setId(UUID.randomUUID());
            admin.setEmail(in.firstAdmin().email().trim().toLowerCase(Locale.ROOT));
            admin.setFullName(in.firstAdmin().fullName().trim());
            admin.setAuthType(User.AuthType.GOOGLE);
            admin.setStatus(User.Status.INVITED);
            admin.setInvitedAt(now);
            admin.setCreatedAt(now);
            admin.setUpdatedAt(now);
            users.saveAndFlush(admin);
            RoleAssignment r = new RoleAssignment();
            r.setId(UUID.randomUUID());
            r.setUserId(admin.getId());
            r.setRole(Role.ODEL_ADMIN);
            r.setScopeType(ScopeType.UNIVERSITY);
            r.setScopeLabel(t.getName());
            r.setCreatedAt(now);
            r.setCreatedBy(actor.userId());
            roles.saveAndFlush(r);
            invite(t, admin);
            audit.record(new AuditService.Entry(t.getId(), actor, "TENANT_CREATED", "tenant", t.getId(),
                    "Created " + t.getName() + "; invited " + admin.getFullName(), null,
                    Map.of("slug", slug, "webAddress", web == null ? "" : web, "firstAdmin", admin.getEmail())));
            return t;
        }));
    }

    public void resendInvitation(UUID tenantId, AuditService.Actor actor) {
        Tenant t = tenants.findById(tenantId).filter(x -> !x.isPlatform()).orElseThrow(ApiException::notFound);
        TenantContext.runAs(t.getId(), () -> tx.executeWithoutResult(s -> {
            User admin = firstAdmin(t).orElseThrow(ApiException::notFound);
            invite(t, admin);
            audit.record(new AuditService.Entry(t.getId(), actor, "INVITATION_RESENT", "user", admin.getId(),
                    "Resent the invitation to " + admin.getFullName(), null, null));
        }));
    }

    /** The first ODeL admin: the earliest ODEL_ADMIN assignment. Runs in the tenant's context. */
    public java.util.Optional<User> firstAdmin(Tenant t) {
        return roles.findAll().stream().filter(r -> r.getRole() == Role.ODEL_ADMIN)
                .min(java.util.Comparator.comparing(RoleAssignment::getCreatedAt))
                .flatMap(r -> users.findById(r.getUserId()));
    }

    public String portalUrl(Tenant t) {
        String host = t.getWebAddress() != null ? t.getWebAddress() : t.getSlug() + "." + props.baseDomain();
        if ("localhost".equals(props.baseDomain()) && t.getWebAddress() == null) host = host + ":3000";
        return props.webScheme() + "://" + host;
    }

    private void invite(Tenant t, User admin) {
        messages.send(new MessageService.Message(admin.getId(), admin.getEmail(), "staff.invitation", Map.of(
                "fullName", admin.getFullName(), "roleLabel", Role.ODEL_ADMIN.label(), "googleDomain", t.getGoogleDomain(),
                "signInUrl", portalUrl(t) + "/staff/sign-in"), "/staff"));
    }

    private Map<String, String> validate(NewUniversity in) {
        Map<String, String> f = new LinkedHashMap<>();
        if (blank(in.name())) f.put("name", "Enter the university's name.");
        if (blank(in.shortName())) f.put("shortName", "Enter a short name, for example Wesley ODeL.");
        if (blank(in.slug()) || !in.slug().trim().toLowerCase(Locale.ROOT).matches(SLUG))
            f.put("slug", "Use 2 to 31 lowercase letters, numbers or hyphens, starting with a letter.");
        if (!blank(in.webAddress()) && !in.webAddress().trim().toLowerCase(Locale.ROOT).matches(HOST))
            f.put("webAddress", "Enter a web address like odel.wesleyuni.edu.ng, without https://.");
        if (blank(in.googleDomain()) || !in.googleDomain().trim().toLowerCase(Locale.ROOT).matches(HOST))
            f.put("googleDomain", "Enter the staff Google domain, for example wesleyuni.edu.ng.");
        Branding b = in.branding();
        if (b == null || !Contrast.isHex(b.primaryColor())) f.put("primaryColor", "Enter a colour like #1A2368.");
        if (b == null || !Contrast.isHex(b.accentColor())) f.put("accentColor", "Enter a colour like #C27808.");
        if (b != null && b.logoKey() != null && !b.logoKey().matches("logos/[a-f0-9-]{36}\\.(svg|png)"))
            f.put("logoKey", "Upload the logo again.");
        FirstAdmin a = in.firstAdmin();
        if (a == null || blank(a.fullName())) f.put("firstAdmin.fullName", "Enter the first admin's name.");
        if (a == null || blank(a.email()) || !a.email().trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            f.put("firstAdmin.email", "Enter the first admin's email.");
        } else if (!blank(in.googleDomain()) && !a.email().trim().toLowerCase(Locale.ROOT).endsWith("@" + in.googleDomain().trim().toLowerCase(Locale.ROOT))) {
            f.put("firstAdmin.email", "Use an @" + in.googleDomain().trim().toLowerCase(Locale.ROOT) + " address: staff sign in with the university's Google accounts.");
        }
        return f;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
