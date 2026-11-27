package com.odel.notification.service;

import com.odel.notification.service.jobs.EmailJob;
import com.odel.notification.service.jobs.PushJob;
import com.odel.notification.entity.Notification;
import com.odel.notification.entity.PushToken;
import com.odel.notification.repository.NotificationRepository;
import com.odel.notification.repository.PushTokenRepository;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;
import com.odel.notification.service.jobs.*;

import com.odel.notification.service.jobs.JobService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One way to tell people things: email, the in-app bell and push, all from templates.
 * No SMS or WhatsApp (owner's decision, 24 Nov 2026). Email and push are sent by the worker.
 */
@Service
public class MessageService {
    private final Templates templates;
    private final JdbcTemplate jdbc;
    private final JobService jobs;
    private final NotificationRepository notifications;
    private final PushTokenRepository pushTokens;
    private final Clock clock;

    public MessageService(Templates templates, JdbcTemplate jdbc, JobService jobs, NotificationRepository notifications,
                          PushTokenRepository pushTokens, Clock clock) {
        this.templates = templates;
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.notifications = notifications;
        this.pushTokens = pushTokens;
        this.clock = clock;
    }

    /**
     * @param userId may be null for someone without an account yet (then only email goes)
     * @param link   where the bell item and push open, on the person's portal
     */
    public record Message(UUID userId, String email, String templateKey, Map<String, ?> vars, String link, TenantLook tenant) {}

    /** The university's name and colour, sent by the caller so emails carry its branding. */
    public record TenantLook(String name, String shortName, String primaryColor) {}

    /** Runs in the current tenant. Returns the email's outbound id, or null when there is no email template. */
    @Transactional
    public UUID send(Message m) {
        UUID tenantId = TenantContext.require();
        TenantLook tenant = m.tenant();
        Map<String, Object> vars = new HashMap<>(m.vars());
        vars.putIfAbsent("universityName", tenant.name());

        UUID emailId = null;
        var email = templates.find(tenantId, m.templateKey(), Templates.Channel.EMAIL);
        if (email.isPresent() && m.email() != null) {
            String subject = Templates.render(email.get().subject(), vars);
            String body = Templates.render(email.get().body(), vars);
            emailId = UUID.randomUUID();
            jdbc.update("""
                    insert into outbound_messages (id, tenant_id, user_id, channel, template_key, to_address, from_name, subject, body, html_body, status, created_at)
                    values (?, ?, ?, 'EMAIL', ?, ?, ?, ?, ?, ?, 'QUEUED', ?)""",
                    emailId, tenantId, m.userId(), m.templateKey(), m.email(), tenant.shortName(), subject, body, html(tenant, subject, body),
                    Timestamp.from(clock.instant()));
            jobs.enqueue(EmailJob.TYPE, Map.of("id", emailId.toString()));
        }

        if (m.userId() != null) {
            templates.find(tenantId, m.templateKey(), Templates.Channel.IN_APP).ifPresent(t -> {
                Notification n = new Notification();
                n.setId(UUID.randomUUID());
                n.setUserId(m.userId());
                n.setType(m.templateKey());
                n.setTitle(Templates.render(t.subject(), vars));
                n.setBody(Templates.render(t.body(), vars));
                n.setLink(m.link());
                n.setCreatedAt(clock.instant());
                notifications.save(n);
            });
            templates.find(tenantId, m.templateKey(), Templates.Channel.PUSH).ifPresent(t -> {
                for (PushToken token : pushTokens.findByUserId(m.userId())) {
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            insert into outbound_messages (id, tenant_id, user_id, channel, template_key, to_address, subject, body, status, created_at)
                            values (?, ?, ?, 'PUSH', ?, ?, ?, ?, 'QUEUED', ?)""",
                            id, tenantId, m.userId(), m.templateKey(), token.getToken(), Templates.render(t.subject(), vars),
                            Templates.render(t.body(), vars), Timestamp.from(clock.instant()));
                    jobs.enqueue(PushJob.TYPE, Map.of("id", id.toString(), "link", m.link() == null ? "" : m.link()));
                }
            });
        }
        return emailId;
    }

    /** Many messages in one transaction (welcome emails to a whole intake). Returns each email's id, in order. */
    @Transactional
    public java.util.List<UUID> sendAll(java.util.List<Message> messages) {
        java.util.List<UUID> ids = new java.util.ArrayList<>(messages.size());
        for (Message m : messages) ids.add(send(m));
        return ids;
    }

    /** A plain, light email in the university's colour: readable on any phone, no images to download. */
    static String html(TenantLook tenant, String subject, String text) {
        String body = HtmlUtils.htmlEscape(text)
                .replaceAll("(https?://[^\\s<]+)", "<a href=\"$1\" style=\"color:" + tenant.primaryColor() + "\">$1</a>")
                .replace("\n\n", "</p><p>").replace("\n", "<br>");
        return """
                <!doctype html><html><body style="margin:0;background:#f4f4f6;font-family:Arial,sans-serif;color:#1d1d24">
                <div style="max-width:560px;margin:0 auto;background:#fff">
                <div style="background:%s;color:#fff;padding:16px 24px;font-size:16px;font-weight:bold">%s</div>
                <div style="padding:8px 24px 24px;font-size:15px;line-height:1.5"><p>%s</p></div></div></body></html>"""
                .formatted(tenant.primaryColor(), HtmlUtils.htmlEscape(tenant.name()), body);
    }
}
