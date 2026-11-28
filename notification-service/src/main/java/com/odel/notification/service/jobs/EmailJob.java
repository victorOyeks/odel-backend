package com.odel.notification.service.jobs;

import com.odel.notification.service.MailAllowList;
import com.odel.notification.service.TestAddresses;

import com.odel.notification.client.EmailSender;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;
import com.odel.notification.service.jobs.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.odel.notification.service.jobs.JobHandler;
import com.odel.common.tenancy.TenantContext;
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
    private final Clock clock;
    private final MailAllowList allowList;

    public EmailJob(JdbcTemplate jdbc, EmailSender sender, Clock clock, MailAllowList allowList) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.clock = clock;
        this.allowList = allowList;
    }

    @Override public String type() { return TYPE; }

    record Row(String to, String fromName, String subject, String body, String html, String status, String attachments) {}

    @Override
    public void handle(JsonNode payload) throws Exception {
        UUID id = UUID.fromString(payload.get("id").asText());
        Row row = jdbc.queryForObject("select to_address, from_name, subject, body, html_body, status, attachments::text from outbound_messages where id = ? and tenant_id = ?",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                id, TenantContext.require());
        if (row == null || "SENT".equals(row.status()) || "SKIPPED".equals(row.status())) return; // safe to run twice
        if (TestAddresses.isTest(row.to())) {
            // A reserved test domain (.test, .example, example.com...) can never receive mail: recorded, never sent.
            jdbc.update("update outbound_messages set status = 'SKIPPED', last_error = 'Not sent: test address' where id = ?", id);
            return;
        }
        if (!allowList.allows(row.to())) {
            // Dev only (ODEL_MAIL_ALLOW_DOMAINS): recorded and readable in the dev email log, never sent.
            jdbc.update("update outbound_messages set status = 'SKIPPED', last_error = ? where id = ?", MailAllowList.REASON, id);
            return;
        }
        String fromName = row.fromName();
        try {
            sender.send(row.to(), fromName, row.subject(), row.body(), row.html(), EmailSender.Attachment.fromJson(row.attachments()));
            jdbc.update("update outbound_messages set status = 'SENT', sent_at = ?, attempts = attempts + 1 where id = ?",
                    Timestamp.from(clock.instant()), id);
        } catch (Exception e) {
            jdbc.update("update outbound_messages set attempts = attempts + 1, last_error = ? where id = ?", e.getMessage(), id);
            throw e;
        }
    }
}
