package com.odel.common.audit;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.files.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.RequestInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * One audit log for sign-ins, role changes and sensitive actions: who, when, from where, old and new value.
 * Written in the caller's transaction, so an action and its audit row succeed or fail together.
 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record Actor(UUID userId, UUID tenantId, String name, String role) {
        public static final Actor SYSTEM = new Actor(null, null, "System", null);
    }

    public record Entry(UUID tenantId, Actor actor, String action, String entityType, UUID entityId,
                        String summary, Object oldValue, Object newValue) {}

    public void record(Entry e) {
        jdbc.update("""
                insert into app.audit_log (id, tenant_id, actor_user_id, actor_tenant_id, actor_name, actor_role, action,
                    entity_type, entity_id, summary, old_value, new_value, ip, user_agent, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""",
                UUID.randomUUID(), e.tenantId(), e.actor().userId(), e.actor().tenantId(), e.actor().name(), e.actor().role(),
                e.action(), e.entityType(), e.entityId(), e.summary(), toJson(e.oldValue()), toJson(e.newValue()),
                RequestInfo.ip(), RequestInfo.userAgent(), Timestamp.from(clock.instant()));
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
