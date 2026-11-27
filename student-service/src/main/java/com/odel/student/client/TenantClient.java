package com.odel.student.client;

import com.odel.common.internal.InternalClient;
import com.odel.common.tenancy.TenantContext;
import com.odel.student.dto.NotificationDtos.TenantLook;
import com.odel.student.dto.TenantDtos.PortalView;
import com.odel.student.dto.TenantDtos.TenantView;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The university's name, colour and portal address (for emails and links), from tenant-service, cached for a minute. */
@Component
public class TenantClient {
    private final InternalClient internal;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    record Cached(TenantView tenant, String portalUrl, long at) {}

    public TenantClient(InternalClient internal) {
        this.internal = internal;
    }

    public TenantView tenant() {
        return cached().tenant();
    }

    public String portalUrl() {
        return cached().portalUrl();
    }

    public TenantLook look() {
        TenantView t = tenant();
        return new TenantLook(t.name(), t.shortName(), t.primaryColor() == null ? "#1A2368" : t.primaryColor());
    }

    private Cached cached() {
        UUID id = TenantContext.require();
        Cached c = cache.get(id);
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return c;
        TenantView t = internal.get(InternalClient.Service.TENANT, "/internal/tenants/" + id, TenantView.class);
        PortalView p = internal.get(InternalClient.Service.TENANT, "/internal/tenants/" + id + "/portal", PortalView.class);
        c = new Cached(t, p.portalUrl(), System.currentTimeMillis());
        cache.put(id, c);
        return c;
    }
}
