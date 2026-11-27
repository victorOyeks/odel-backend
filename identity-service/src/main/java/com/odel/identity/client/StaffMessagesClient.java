package com.odel.identity.client;

import com.odel.common.internal.InternalClient;
import com.odel.identity.User;
import com.odel.identity.dto.StaffDtos.TenantLook;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Staff emails and bell items through notification-service. */
@Component
public class StaffMessagesClient {
    private final InternalClient internal;
    private final TenantLookClient tenants;

    public StaffMessagesClient(InternalClient internal, TenantLookClient tenants) {
        this.internal = internal;
        this.tenants = tenants;
    }

    /** The invitation (template staff.invitation), e.g. roleLabel "Lecturer for CSC 201 · Computer Science". */
    public void invitation(User u, String roleLabel) {
        TenantLook t = tenants.current();
        send(u, "staff.invitation", Map.of("fullName", u.getFullName(), "roleLabel", roleLabel, "googleDomain", t.googleDomain(),
                "signInUrl", signIn(t)), t);
    }

    public void twoStepReset(User u, String resetBy) {
        TenantLook t = tenants.current();
        send(u, "staff.two_step_reset", Map.of("fullName", u.getFullName(), "resetBy", resetBy, "signInUrl", signIn(t)), t);
    }

    private static String signIn(TenantLook t) {
        return (t.portalUrl() == null ? "" : t.portalUrl()) + "/staff/sign-in";
    }

    private void send(User u, String template, Map<String, Object> vars, TenantLook t) {
        internal.post(InternalClient.Service.NOTIFICATION, "/internal/messages", Map.of(
                "userId", u.getId(), "email", u.getEmail(), "templateKey", template, "vars", vars, "link", "/staff",
                "tenant", Map.of("name", t.name(), "shortName", t.shortName(), "primaryColor", t.primaryColor())), Map.class);
    }
}
