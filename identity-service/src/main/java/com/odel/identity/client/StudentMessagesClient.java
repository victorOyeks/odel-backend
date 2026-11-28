package com.odel.identity.client;

import com.odel.common.internal.InternalClient;
import com.odel.identity.dto.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * identity-service → notification-service for the student's own account notices (B3 profile): the email-change code, and the
 * "your email / password / recovery codes changed" notices. With a userId the bell gets the message too (when the template has one).
 */
@Component
public class StudentMessagesClient {
    private static final Logger log = LoggerFactory.getLogger(StudentMessagesClient.class);
    private final InternalClient internal;

    public StudentMessagesClient(InternalClient internal) {
        this.internal = internal;
    }

    /** Must reach notification-service (a code the student is waiting for): failures are thrown. */
    public void send(TenantInfo tenant, UUID bellUserId, String email, String templateKey, Map<String, ?> vars, String link) {
        Map<String, Object> look = new HashMap<>();
        look.put("name", tenant.name());
        look.put("shortName", tenant.shortName());
        look.put("primaryColor", tenant.primaryColor() == null ? "#1A2368" : tenant.primaryColor());
        Map<String, Object> msg = new HashMap<>();
        msg.put("userId", bellUserId);
        msg.put("email", email);
        msg.put("templateKey", templateKey);
        msg.put("vars", vars);
        msg.put("link", link);
        msg.put("tenant", look);
        internal.post(InternalClient.Service.NOTIFICATION, "/internal/messages", msg, Map.class);
    }

    /** A notice after the change is already made: logged, never undoes the change. */
    public void notice(TenantInfo tenant, UUID bellUserId, String email, String templateKey, Map<String, ?> vars, String link) {
        try {
            send(tenant, bellUserId, email, templateKey, vars, link);
        } catch (RuntimeException e) {
            log.warn("Notice {} to user {} not sent: {}", templateKey, bellUserId, e.getMessage());
        }
    }
}
