package com.odel.tenant.service;

import com.odel.tenant.entity.Tenant;
import com.odel.tenant.repository.TenantRepository;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

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
    public Tenant resolve(String host) {
        if (host == null || host.isBlank()) return null;
        String name = host.toLowerCase(Locale.ROOT).trim();
        int colon = name.lastIndexOf(':');
        if (colon > 0 && name.indexOf(']') < colon) name = name.substring(0, colon);
        String base = props.baseDomain().toLowerCase(Locale.ROOT);
        String console = props.consoleHost() == null ? "console." + base : props.consoleHost().toLowerCase(Locale.ROOT);
        if (name.equals(console)) return tenants.findById(TenantContext.PLATFORM_TENANT_ID).orElse(null);
        var byAddress = tenants.findByWebAddressIgnoreCase(name);
        if (byAddress.isPresent()) return byAddress.get();
        String suffix = "." + base;
        if (name.endsWith(suffix)) {
            String slug = name.substring(0, name.length() - suffix.length());
            if (!slug.contains(".") && !slug.equals("platform")) {
                return tenants.findBySlug(slug).filter(t -> !t.isPlatform()).orElse(null);
            }
        }
        return null;
    }
}
