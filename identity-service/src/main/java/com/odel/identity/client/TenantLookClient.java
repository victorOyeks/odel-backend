package com.odel.identity.client;

import com.odel.common.internal.InternalClient;
import com.odel.common.tenancy.TenantContext;
import com.odel.identity.dto.StaffDtos.TenantLook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The university's name, Google domain, colour and portal address for invitations (tenant-service, cached for a minute). */
@Component
public class TenantLookClient {
    private final InternalClient internal;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(TenantLook t, long at) {}

    public TenantLookClient(InternalClient internal) {
        this.internal = internal;
    }

    public TenantLook current() {
        UUID id = TenantContext.require();
        Cached c = cache.get(id);
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return c.t();
        TenantLook t = internal.get(InternalClient.Service.TENANT, "/internal/tenants/" + id, TenantLook.class);
        cache.put(id, new Cached(t, System.currentTimeMillis()));
        return t;
    }
}
