package com.odel.notification.service.jobs;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Queues background work in Postgres, in the caller's transaction (so a job exists only if its cause committed). */
@Service
public class JobService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public JobService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public UUID enqueue(String type, Object payload) {
        return enqueue(type, payload, clock.instant());
    }

    public UUID enqueue(String type, Object payload, Instant runAt) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into jobs (id, tenant_id, type, payload, status, run_at, created_at)
                    values (?, ?, ?, ?::jsonb, 'QUEUED', ?, ?)""",
                    id, TenantContext.require(), type, json.writeValueAsString(payload), Timestamp.from(runAt), Timestamp.from(clock.instant()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }
}
