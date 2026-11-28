package com.odel.payment.service;

import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.payment.client.PeerClient;
import org.springframework.stereotype.Service;

/** Who acts, for the audit log: the signed-in person's name from identity-service. */
@Service
public class ActorService {
    private final PeerClient peers;

    public ActorService(PeerClient peers) {
        this.peers = peers;
    }

    public AuditService.Actor actor(CurrentUser cu) {
        return new AuditService.Actor(cu.userId(), cu.tenantId(), peers.personName(cu.userId()), cu.activeRole() == null ? null : cu.activeRole().name());
    }

    public AuditService.Actor student(CurrentUser cu, String fullName) {
        return new AuditService.Actor(cu.userId(), cu.tenantId(), fullName, "STUDENT");
    }
}
