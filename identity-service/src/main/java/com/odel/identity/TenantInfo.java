package com.odel.identity;

import com.odel.common.internal.InternalClient;
import com.odel.common.tenancy.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** What identity-service needs to know about a university, from tenant-service (cached for a minute). */
public record TenantInfo(UUID id, String kind, String slug, String name, String shortName, String googleDomain, String status,
                         String primaryColor, String accentColor) {
    public boolean isPlatform() { return "PLATFORM".equals(kind); }

    @Component
    public static class Client {
        private final InternalClient internal;
        private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

        record Cached(TenantInfo info, long at) {}

        public Client(InternalClient internal) {
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
}
