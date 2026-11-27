package com.odel.tenancy;

import com.odel.common.OdelProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class HostResolver {
    private final TenantRepository tenants;
    private final OdelProperties props;

    public HostResolver(TenantRepository tenants, OdelProperties props) {
        this.tenants = tenants;
        this.props = props;
    }

    /** host may include a port ("wesley.localhost:3000"). */
    public ResolvedHost resolve(String host) {
        if (host == null || host.isBlank()) return new ResolvedHost(host, null);
        String name = host.toLowerCase(Locale.ROOT).trim();
        int colon = name.lastIndexOf(':');
        if (colon > 0 && name.indexOf(']') < colon) name = name.substring(0, colon);
        String base = props.baseDomain().toLowerCase(Locale.ROOT);
        if (name.equals("console." + base)) {
            return new ResolvedHost(host, tenants.findById(TenantContext.PLATFORM_TENANT_ID).orElse(null));
        }
        var byAddress = tenants.findByWebAddressIgnoreCase(name);
        if (byAddress.isPresent()) return new ResolvedHost(host, byAddress.get());
        String suffix = "." + base;
        if (name.endsWith(suffix)) {
            String slug = name.substring(0, name.length() - suffix.length());
            if (!slug.contains(".") && !slug.equals("platform")) {
                return new ResolvedHost(host, tenants.findBySlug(slug).filter(t -> !t.isPlatform()).orElse(null));
            }
        }
        return new ResolvedHost(host, null);
    }
}
