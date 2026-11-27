package com.odel.tenant.controller.internal;

import com.odel.tenant.dto.TenantInternalDtos.*;

import com.odel.tenant.service.TenantSetupService;
import com.odel.tenant.service.CustomDomainService;
import com.odel.tenant.service.HostResolver;
import com.odel.tenant.entity.Tenant;
import com.odel.tenant.repository.TenantRepository;

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

    @PostMapping("/custom-domains/{tenantId}/result")
    public void domainResult(@PathVariable UUID tenantId, @RequestBody CertResult body) {
        domains.result(tenantId, body.domain(), body.ok(), body.error());
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
        // portalUrl: where emails should send people (the university's own domain once live, else its platform address).
        return tenants.findById(id).map(t -> TenantView.of(t, t.isPlatform() ? null : setup.portalUrl(t))).orElseThrow(ApiException::notFound);
    }
}
