package com.odel.academic.client;

import com.odel.academic.dto.PeerDtos.Tenant;
import com.odel.common.internal.InternalClient;
import com.odel.common.tenancy.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The university's name, colour and portal address, from tenant-service (cached for a minute). */
@Component
public class TenantClient {
    private final InternalClient internal;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Tenant t, long at) {}

    public TenantClient(InternalClient internal) {
        this.internal = internal;
    }

    public Tenant current() {
        UUID id = TenantContext.require();
        Cached c = cache.get(id);
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return c.t();
        Tenant t = internal.get(InternalClient.Service.TENANT, "/internal/tenants/" + id, Tenant.class);
        cache.put(id, new Cached(t, System.currentTimeMillis()));
        return t;
    }
}
