package com.odel.registration.service;

import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.registration.client.IdentityClient;
import org.springframework.stereotype.Service;

/** Who is acting, for the audit log and the decisions: the signed-in person's name from identity-service. */
@Service
public class ActorService {
    private final IdentityClient identity;

    public ActorService(IdentityClient identity) {
        this.identity = identity;
    }

    public AuditService.Actor actor(CurrentUser cu) {
        String name;
        try {
            name = identity.staff(cu.userId()).fullName();
        } catch (RuntimeException e) {
            name = "Unknown";
        }
        return new AuditService.Actor(cu.userId(), cu.tenantId(), name, cu.activeRole() == null ? null : cu.activeRole().name());
    }
}
