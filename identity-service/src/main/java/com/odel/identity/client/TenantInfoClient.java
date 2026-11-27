package com.odel.identity.client;

import com.odel.common.internal.InternalClient;
import com.odel.common.tenancy.TenantContext;
import com.odel.identity.dto.TenantInfo;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reads a university from tenant-service (cached for a minute). */
@Component
public class TenantInfoClient {
    private final InternalClient internal;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    record Cached(TenantInfo info, long at) {}

    public TenantInfoClient(InternalClient internal) {
        this.internal = internal;
    }

    public TenantInfo get(UUID id) {
        Cached c = cache.get(id);
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return c.info();
        TenantInfo info = TenantContext.runAs(id, () -> internal.get(InternalClient.Service.TENANT, "/internal/tenants/" + id, TenantInfo.class));
        cache.put(id, new Cached(info, System.currentTimeMillis()));
        return info;
    }
}
