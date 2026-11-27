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

    public TenantInternalController(HostResolver hosts, TenantRepository tenants) {
        this.hosts = hosts;
        this.tenants = tenants;
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
}
