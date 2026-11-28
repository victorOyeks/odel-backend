package com.odel.registration.client;

import com.odel.common.internal.InternalClient;
import com.odel.registration.dto.PeerDtos.StaffView;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Staff names and emails (for decisions, the audit log and the coordinator's emails), cached for five minutes. */
@Component
public class IdentityClient {
    private final InternalClient internal;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    record Cached(StaffView staff, long at) {}

    public IdentityClient(InternalClient internal) {
        this.internal = internal;
    }

    public StaffView staff(UUID userId) {
        Cached c = cache.get(userId);
        if (c != null && System.currentTimeMillis() - c.at() < 300_000) return c.staff();
        StaffView s = internal.get(InternalClient.Service.IDENTITY, "/internal/users/" + userId, StaffView.class);
        cache.put(userId, new Cached(s, System.currentTimeMillis()));
        return s;
    }
}
