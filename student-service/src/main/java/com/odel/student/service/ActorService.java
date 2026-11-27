package com.odel.student.service;

import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.student.client.IdentityClient;
import com.odel.student.dto.IdentityDtos.StaffView;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who is acting (for the audit log and the history): the signed-in staff member's name, from identity-service, cached. */
@Service
public class ActorService {
    private final IdentityClient identity;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    record Cached(StaffView staff, long at) {}

    public ActorService(IdentityClient identity) {
        this.identity = identity;
    }

    public StaffView staff(CurrentUser cu) {
        Cached c = cache.get(cu.userId());
        if (c != null && System.currentTimeMillis() - c.at() < 300_000) return c.staff();
        StaffView s = identity.staff(cu.userId());
        cache.put(cu.userId(), new Cached(s, System.currentTimeMillis()));
        return s;
    }

    public AuditService.Actor actor(CurrentUser cu) {
        StaffView s = staff(cu);
        return new AuditService.Actor(cu.userId(), cu.tenantId(), s.fullName(), cu.activeRole() == null ? null : cu.activeRole().name());
    }
}
