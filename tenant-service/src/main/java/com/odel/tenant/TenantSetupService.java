package com.odel.tenant;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.audit.AuditService;
import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.security.Role;
import com.odel.common.security.ScopeType;
import com.odel.tenant.Tenant;
import com.odel.common.tenancy.TenantContext;
import com.odel.tenant.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.odel.common.internal.InternalClient;

/** The provider adds a university (D10 SA-02 to SA-05b, smallest form for B0): tenant, branding, first admin. */
@Service
public class TenantSetupService {
    public static final String SLUG = "^[a-z][a-z0-9-]{1,30}$";
    public static final String HOST = "^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$";
    private static final java.util.Set<String> RESERVED = java.util.Set.of("console", "platform", "www", "api", "admin", "app", "staging", "mail");

    private final TenantRepository tenants;
    private final InternalClient internal;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final OdelProperties props;
    private final Clock clock;

    public TenantSetupService(TenantRepository tenants, InternalClient internal, AuditService audit, TransactionTemplate tx, OdelProperties props, Clock clock) {
        this.tenants = tenants;
        this.internal = internal;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /** Reserved for tests and examples: they can never have a real DNS record, so they are refused as a university's own domain. */
    static boolean reservedDomain(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        String tld = h.substring(h.lastIndexOf('.') + 1);
        if (java.util.Set.of("test", "example", "invalid", "localhost", "local").contains(tld)) return true;
        return java.util.stream.Stream.of("example.com", "example.org", "example.net").anyMatch(d -> h.equals(d) || h.endsWith("." + d));
    }

    static final String RESERVED_MESSAGE = "Use a real domain the university controls; test domains like .test or example.com can never be reached.";

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
        t.setWebAddressStatus(web == null ? Tenant.WebAddressStatus.NONE : Tenant.WebAddressStatus.WAITING_DNS);
        t.setGoogleDomain(in.googleDomain().trim().toLowerCase(Locale.ROOT));
        t.setStatus(Tenant.Status.SETTING_UP);
        t.setPrimaryColor(primary);
        t.setAccentColor(accent);
        t.setLogoKey(in.branding().logoKey());
        t.setCreatedAt(now);
        t.setCreatedBy(actor.userId());
        t.setUpdatedAt(now);

        t.setCreatedByName(actor.name());

        // 1. The university itself (its own row; every other record carries its id).
        tx.executeWithoutResult(st -> tenants.saveAndFlush(t));
        try {
            return TenantContext.runAs(t.getId(), () -> {
                // 2. Its first ODeL admin, in identity-service.
                Admin admin = internal.post(InternalClient.Service.IDENTITY, "/internal/users", Map.of(
                        "fullName", in.firstAdmin().fullName().trim(),
                        "email", in.firstAdmin().email().trim().toLowerCase(Locale.ROOT),
                        "role", "ODEL_ADMIN", "scopeType", "UNIVERSITY", "scopeLabel", t.getName(),
                        "createdBy", actor.userId() == null ? "" : actor.userId().toString()), Admin.class);
                // 3. The invitation email and bell item, through notification-service.
                invite(t, admin);
                tx.executeWithoutResult(st -> audit.record(new AuditService.Entry(t.getId(), actor, "TENANT_CREATED", "tenant", t.getId(),
                        "Created " + t.getName() + "; invited " + admin.fullName(), null,
                        Map.of("slug", slug, "webAddress", web == null ? "" : web, "firstAdmin", admin.email()))));
                return t;
            });
        } catch (RuntimeException e) {
            // Nothing is half-created: without its admin the university is removed again.
            tx.executeWithoutResult(st -> tenants.deleteById(t.getId()));
            throw e;
        }
    }

    /** webAddress: a new own domain (checked again from scratch), or "" to remove it. */
    public record Changes(String name, String shortName, String webAddress, Branding branding) {}

    /** SA-01b Edit: name and branding. Colours pass the same readability check as on creation. */
    public Tenant update(UUID id, Changes in, AuditService.Actor actor) {
        return tx.execute(st -> {
            Tenant t = tenants.findById(id).filter(x -> !x.isPlatform()).orElseThrow(ApiException::notFound);
            Map<String, Object> before = Map.of("name", t.getName(), "shortName", t.getShortName(), "primaryColor", t.getPrimaryColor(),
                    "accentColor", t.getAccentColor(), "logoKey", t.getLogoKey() == null ? "" : t.getLogoKey());
            Map<String, String> fields = new LinkedHashMap<>();
            if (in.name() != null && in.name().isBlank()) fields.put("name", "Enter the university's name.");
            if (in.shortName() != null && in.shortName().isBlank()) fields.put("shortName", "Enter a short name.");
            Branding b = in.branding();
            if (b != null) {
                if (b.primaryColor() != null && !Contrast.isHex(b.primaryColor())) fields.put("primaryColor", "Enter a colour like #1A2368.");
                if (b.accentColor() != null && !Contrast.isHex(b.accentColor())) fields.put("accentColor", "Enter a colour like #C27808.");
                if (b.logoKey() != null && !b.logoKey().isEmpty() && !b.logoKey().matches("logos/[a-f0-9-]{36}\\.(svg|png)")) fields.put("logoKey", "Upload the logo again.");
            }
            String web = in.webAddress() == null ? null : in.webAddress().trim().toLowerCase(Locale.ROOT);
            if (web != null && !web.isEmpty() && !web.matches(HOST)) fields.put("webAddress", "Enter a web address like odl.wesleyuni.edu.ng, without https://.");
            else if (web != null && !web.isEmpty() && reservedDomain(web)) fields.put("webAddress", RESERVED_MESSAGE);
            if (web != null && !web.isEmpty() && !web.equals(t.getWebAddress()) && tenants.existsByWebAddressIgnoreCase(web)) {
                throw new ApiException(HttpStatus.CONFLICT, "web_address_taken", web + " is already used by another university.");
            }
            if (!fields.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Some details need fixing.", fields);
            if (web != null && !web.equals(t.getWebAddress() == null ? "" : t.getWebAddress())) {
                t.setWebAddress(web.isEmpty() ? null : web);
                t.setWebAddressStatus(web.isEmpty() ? Tenant.WebAddressStatus.NONE : Tenant.WebAddressStatus.WAITING_DNS);
                t.setWebAddressError(null);
                t.setWebAddressCheckedAt(null);
                t.setWebAddressCertAttemptAt(null);
                t.setWebAddressLiveAt(null);
            }
            if (in.name() != null) t.setName(in.name().trim());
            if (in.shortName() != null) t.setShortName(in.shortName().trim());
            if (b != null) {
                if (b.primaryColor() != null) {
                    String primary = b.primaryColor().toUpperCase(Locale.ROOT);
                    double ratio = Contrast.ratio(primary, "#FFFFFF");
                    if (ratio < Contrast.MIN) {
                        throw ApiException.fields(HttpStatus.UNPROCESSABLE_ENTITY, "contrast", "The main colour is hard to read with white text on it.",
                                Map.of("primaryColor", "White text on " + primary + " is " + Contrast.format(ratio) + "; it needs 4.5 : 1"));
                    }
                    t.setPrimaryColor(primary);
                }
                if (b.accentColor() != null) t.setAccentColor(b.accentColor().toUpperCase(Locale.ROOT));
                if (b.logoKey() != null) t.setLogoKey(b.logoKey().isEmpty() ? null : b.logoKey());
            }
            t.setUpdatedAt(clock.instant());
            audit.record(new AuditService.Entry(t.getId(), actor, "TENANT_UPDATED", "tenant", t.getId(),
                    "Updated " + t.getName() + "'s details and branding", before,
                    Map.of("name", t.getName(), "shortName", t.getShortName(), "primaryColor", t.getPrimaryColor(),
                            "accentColor", t.getAccentColor(), "logoKey", t.getLogoKey() == null ? "" : t.getLogoKey())));
            return t;
        });
    }

    /** The first admin as identity-service reports it. */
    public record Admin(UUID id, String fullName, String email, Instant invitedAt, boolean accepted, Instant acceptedAt) {}

    public void resendInvitation(UUID tenantId, AuditService.Actor actor) {
        Tenant t = tenants.findById(tenantId).filter(x -> !x.isPlatform()).orElseThrow(ApiException::notFound);
        TenantContext.runAs(t.getId(), () -> {
            Admin admin = firstAdmin(t).orElseThrow(ApiException::notFound);
            invite(t, admin);
            tx.executeWithoutResult(st -> audit.record(new AuditService.Entry(t.getId(), actor, "INVITATION_RESENT", "user", admin.id(),
                    "Resent the invitation to " + admin.fullName(), null, null)));
        });
    }

    /** The university's first ODeL admin, from identity-service. */
    public java.util.Optional<Admin> firstAdmin(Tenant t) {
        try {
            return java.util.Optional.of(TenantContext.runAs(t.getId(),
                    () -> internal.get(InternalClient.Service.IDENTITY, "/internal/first-admin", Admin.class)));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) return java.util.Optional.empty();
            throw e;
        }
    }

    /** Where people should go: the university's own domain once it is live, otherwise its platform address. */
    public String portalUrl(Tenant t) {
        if (t.getWebAddress() != null && t.getWebAddressStatus() == Tenant.WebAddressStatus.LIVE) {
            return props.webScheme() + "://" + t.getWebAddress();
        }
        return platformUrl(t);
    }

    /** <slug>.<base domain>: works as soon as the university exists (wildcard DNS and certificate). */
    public String platformUrl(Tenant t) {
        String host = t.getSlug() + "." + props.baseDomain();
        if ("localhost".equals(props.baseDomain())) host = host + ":3000";
        return props.webScheme() + "://" + host;
    }

    private void invite(Tenant t, Admin admin) {
        Map<String, Object> vars = Map.of("fullName", admin.fullName(), "roleLabel", "ODeL admin",
                "googleDomain", t.getGoogleDomain(), "signInUrl", portalUrl(t) + "/staff/sign-in");
        internal.post(InternalClient.Service.NOTIFICATION, "/internal/messages", Map.of(
                "userId", admin.id(), "email", admin.email(), "templateKey", "staff.invitation", "vars", vars, "link", "/staff",
                "tenant", Map.of("name", t.getName(), "shortName", t.getShortName(), "primaryColor", t.getPrimaryColor())), Void.class);
    }

    private Map<String, String> validate(NewUniversity in) {
        Map<String, String> f = new LinkedHashMap<>();
        if (blank(in.name())) f.put("name", "Enter the university's name.");
        if (blank(in.shortName())) f.put("shortName", "Enter a short name, for example Wesley ODeL.");
        if (blank(in.slug()) || !in.slug().trim().toLowerCase(Locale.ROOT).matches(SLUG))
            f.put("slug", "Use 2 to 31 lowercase letters, numbers or hyphens, starting with a letter.");
        if (!blank(in.webAddress()) && !in.webAddress().trim().toLowerCase(Locale.ROOT).matches(HOST))
            f.put("webAddress", "Enter a web address like odel.wesleyuni.edu.ng, without https://.");
        else if (!blank(in.webAddress()) && reservedDomain(in.webAddress().trim())) f.put("webAddress", RESERVED_MESSAGE);
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
