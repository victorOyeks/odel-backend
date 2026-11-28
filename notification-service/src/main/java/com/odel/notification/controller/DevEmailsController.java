package com.odel.notification.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Development only (the dev sign-in stand-in is on): the last 20 emails, sent, skipped or logged; ?to= narrows to one address. */
@RestController
@ConditionalOnProperty(name = "odel.google.dev-stub", havingValue = "true")
public class DevEmailsController {
    private final JdbcTemplate jdbc;

    public DevEmailsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/dev/emails")
    public List<Map<String, Object>> emails(@RequestParam(required = false) String to) {
        if (to != null && !to.isBlank()) {
            return jdbc.queryForList("""
                    select created_at, tenant_id, to_address, subject, status, last_error, body,
                        (select coalesce(string_agg(a->>'fileName', ', '), '') from jsonb_array_elements(coalesce(attachments, '[]'::jsonb)) a) as attachments
                    from outbound_messages where channel = 'EMAIL' and lower(to_address) = lower(?) order by created_at desc limit 20""", to.trim());
        }
        return jdbc.queryForList("""
                select created_at, tenant_id, to_address, subject, status, last_error, body,
                        (select coalesce(string_agg(a->>'fileName', ', '), '') from jsonb_array_elements(coalesce(attachments, '[]'::jsonb)) a) as attachments
                from outbound_messages where channel = 'EMAIL' order by created_at desc limit 20""");
    }
}
