package com.odel.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.odel.jobs.JobHandler;
import com.odel.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Component
public class PushJob implements JobHandler {
    public static final String TYPE = "message.push";
    private final JdbcTemplate jdbc;
    private final PushSender sender;
    private final Clock clock;

    public PushJob(JdbcTemplate jdbc, PushSender sender, Clock clock) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.clock = clock;
    }

    @Override public String type() { return TYPE; }

    @Override
    public void handle(JsonNode payload) throws Exception {
        UUID id = UUID.fromString(payload.get("id").asText());
        UUID tenant = TenantContext.require();
        var rows = jdbc.queryForList("select to_address, subject, body, status from outbound_messages where id = ? and tenant_id = ?", id, tenant);
        if (rows.isEmpty() || "SENT".equals(rows.get(0).get("status"))) return;
        var row = rows.get(0);
        String token = (String) row.get("to_address");
        PushSender.Result r = sender.send(token, (String) row.get("subject"), (String) row.get("body"), payload.path("link").asText(null));
        if (r == PushSender.Result.TOKEN_GONE) {
            jdbc.update("delete from push_tokens where tenant_id = ? and token = ?", tenant, token);
            jdbc.update("update outbound_messages set status = 'FAILED', last_error = 'Browser unsubscribed', attempts = attempts + 1 where id = ?", id);
            return;
        }
        jdbc.update("update outbound_messages set status = 'SENT', sent_at = ?, attempts = attempts + 1 where id = ?", Timestamp.from(clock.instant()), id);
    }
}
