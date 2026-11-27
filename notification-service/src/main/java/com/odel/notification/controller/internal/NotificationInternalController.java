package com.odel.notification.controller.internal;

import com.odel.notification.dto.MessageDtos;

import com.odel.notification.service.MessageService;

import com.odel.common.tenancy.TenantContext;
import com.odel.notification.dto.MessageDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Other services send messages through here (X-Internal-Key; the university in X-Odel-Tenant-Id). */
@RestController
@RequestMapping("/internal")
public class NotificationInternalController {
    private final MessageService messages;
    private final JdbcTemplate jdbc;

    public NotificationInternalController(MessageService messages, JdbcTemplate jdbc) {
        this.messages = messages;
        this.jdbc = jdbc;
    }

    @PostMapping("/messages")
    public ResponseEntity<Map<String, Object>> send(@RequestBody MessageDtos.Message message) {
        TenantContext.require();
        UUID emailId = messages.send(message);
        return ResponseEntity.ok(emailId == null ? Map.of() : Map.of("emailId", emailId));
    }

    /** Many messages at once (a whole intake's welcome emails), in one transaction. */
    @PostMapping("/messages/bulk")
    public BulkResponse bulk(@RequestBody BulkRequest body) {
        TenantContext.require();
        return new BulkResponse(messages.sendAll(body.messages() == null ? List.of() : body.messages()));
    }

    /**
     * Where each email stands. A message still QUEUED whose job has given up is reported FAILED with the job's last error,
     * so a delivery report never shows "sending" for ever.
     */
    @PostMapping("/messages/status")
    public StatusResponse status(@RequestBody StatusRequest body) {
        UUID tenant = TenantContext.require();
        List<Status> out = new ArrayList<>();
        List<UUID> ids = body.ids() == null ? List.of() : body.ids();
        for (int from = 0; from < ids.size(); from += 1000) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + 1000));
            out.addAll(jdbc.query("""
                    select m.id, m.status, m.last_error, m.sent_at,
                           case when m.status = 'QUEUED' then (select j.status from jobs j where j.payload->>'id' = m.id::text
                            and j.tenant_id = m.tenant_id order by j.created_at desc limit 1) end as job_status,
                           case when m.status = 'QUEUED' then (select j.last_error from jobs j where j.payload->>'id' = m.id::text
                            and j.tenant_id = m.tenant_id order by j.created_at desc limit 1) end as job_error
                    from outbound_messages m where m.tenant_id = ? and m.id = any (?)""",
                    (rs, i) -> {
                        String status = rs.getString("status");
                        String error = rs.getString("last_error");
                        if ("QUEUED".equals(status) && "FAILED".equals(rs.getString("job_status"))) {
                            status = "FAILED";
                            if (error == null) error = rs.getString("job_error");
                        }
                        var sent = rs.getTimestamp("sent_at");
                        return new Status(rs.getObject("id", UUID.class), status, error, sent == null ? null : sent.toInstant());
                    }, tenant, chunk.toArray(new UUID[0])));
        }
        return new StatusResponse(out);
    }
}
