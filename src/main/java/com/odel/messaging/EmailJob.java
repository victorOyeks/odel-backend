package com.odel.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.odel.jobs.JobHandler;
import com.odel.tenancy.TenantContext;
import com.odel.tenancy.TenantRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Component
public class EmailJob implements JobHandler {
    public static final String TYPE = "message.email";
    private final JdbcTemplate jdbc;
    private final EmailSender sender;
    private final TenantRepository tenants;
    private final Clock clock;

    public EmailJob(JdbcTemplate jdbc, EmailSender sender, TenantRepository tenants, Clock clock) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Override public String type() { return TYPE; }

    record Row(String to, String subject, String body, String html, String status) {}

    @Override
    public void handle(JsonNode payload) throws Exception {
        UUID id = UUID.fromString(payload.get("id").asText());
        Row row = jdbc.queryForObject("select to_address, subject, body, html_body, status from outbound_messages where id = ? and tenant_id = ?",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                id, TenantContext.require());
        if (row == null || "SENT".equals(row.status())) return; // already sent: safe to run twice
        String fromName = tenants.findById(TenantContext.require()).map(t -> t.getShortName()).orElse(null);
        try {
            sender.send(row.to(), fromName, row.subject(), row.body(), row.html());
            jdbc.update("update outbound_messages set status = 'SENT', sent_at = ?, attempts = attempts + 1 where id = ?",
                    Timestamp.from(clock.instant()), id);
        } catch (Exception e) {
            jdbc.update("update outbound_messages set attempts = attempts + 1, last_error = ? where id = ?", e.getMessage(), id);
            throw e;
        }
    }
}
