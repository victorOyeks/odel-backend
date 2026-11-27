package com.odel.tenant;

import com.odel.common.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Called by the gateway and other services only (X-Internal-Key). */
@RestController
@RequestMapping("/internal")
public class TenantInternalController {
    private final HostResolver hosts;
    private final TenantRepository tenants;
    private final CustomDomainService domains;
    private final TenantSetupService setup;

    public TenantInternalController(HostResolver hosts, TenantRepository tenants, CustomDomainService domains, TenantSetupService setup) {
        this.hosts = hosts;
        this.tenants = tenants;
        this.domains = domains;
        this.setup = setup;
    }

    /** The server's odel-custom-domains job: which own domains need a certificate now. */
    @GetMapping("/custom-domains/pending")
    public java.util.List<CustomDomainService.Pending> pendingDomains() {
        return domains.pending();
    }

    public record CertResult(String domain, boolean ok, String error) {}

    @PostMapping("/custom-domains/{tenantId}/result")
    public void domainResult(@PathVariable UUID tenantId, @RequestBody CertResult body) {
        domains.result(tenantId, body.domain(), body.ok(), body.error());
    }

    public record HostView(UUID tenantId, String kind) {}

    public record TenantView(UUID id, String kind, String slug, String name, String shortName, String googleDomain,
                             String status, String primaryColor, String accentColor) {
        static TenantView of(Tenant t) {
            return new TenantView(t.getId(), t.getKind().name(), t.getSlug(), t.getName(), t.getShortName(),
                    t.getGoogleDomain(), t.getStatus().name(), t.getPrimaryColor(), t.getAccentColor());
        }
    }

    /** The gateway asks this for every new host it sees (and caches the answer). */
    @GetMapping("/hosts/resolve")
    public HostView resolve(@RequestParam String host) {
        Tenant t = hosts.resolve(host);
        if (t == null) throw ApiException.notFound();
        return new HostView(t.getId(), t.getKind().name());
    }

    @GetMapping("/tenants/{id}")
    public TenantView tenant(@PathVariable UUID id) {
        return tenants.findById(id).map(TenantView::of).orElseThrow(ApiException::notFound);
    }

    public record PortalView(String portalUrl) {}

    /** Where people should go (the university's own domain once live, else its platform address): links in emails. */
    @GetMapping("/tenants/{id}/portal")
    public PortalView portal(@PathVariable UUID id) {
        return tenants.findById(id).map(t -> new PortalView(setup.portalUrl(t))).orElseThrow(ApiException::notFound);
    }
}
