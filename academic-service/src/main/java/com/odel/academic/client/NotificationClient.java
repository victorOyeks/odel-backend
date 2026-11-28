package com.odel.academic.client;

import com.odel.academic.dto.PeerDtos.Staff;
import com.odel.academic.dto.PeerDtos.Tenant;
import com.odel.common.internal.InternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Messages through notification-service. */
@Component
public class NotificationClient {
    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);
    private final InternalClient internal;
    private final TenantClient tenants;

    public NotificationClient(InternalClient internal, TenantClient tenants) {
        this.internal = internal;
        this.tenants = tenants;
    }

    /** "You have been assigned" email and bell item (template staff.assigned). A failure is logged, never fails the save. */
    public void assigned(Staff s, String what, String sessionName) {
        try {
            Tenant t = tenants.current();
            internal.post(InternalClient.Service.NOTIFICATION, "/internal/messages", Map.of(
                    "userId", s.id(), "email", s.email(), "templateKey", "staff.assigned",
                    "vars", Map.of("fullName", s.fullName(), "what", what, "sessionName", sessionName,
                            "signInUrl", (t.portalUrl() == null ? "" : t.portalUrl()) + "/staff/sign-in",
                            "signInHow", t.staffSignInHow() == null ? "Sign in with your work account" : t.staffSignInHow()),
                    "link", "/staff",
                    "tenant", Map.of("name", t.name(), "shortName", t.shortName(), "primaryColor", t.primaryColor())), Map.class);
        } catch (RuntimeException e) {
            log.warn("Could not send the assignment message to {}: {}", s.id(), e.getMessage());
        }
    }
}
