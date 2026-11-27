package com.odel.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Development only (mail mode "log"): the last 20 emails the platform sent or would have sent. */
@RestController
@ConditionalOnProperty(name = "odel.mail.mode", havingValue = "log")
public class DevEmailsController {
    private final JdbcTemplate jdbc;

    public DevEmailsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/dev/emails")
    public List<Map<String, Object>> emails() {
        return jdbc.queryForList("""
                select created_at, tenant_id, to_address, subject, status, body
                from outbound_messages where channel = 'EMAIL' order by created_at desc limit 20""");
    }
}
