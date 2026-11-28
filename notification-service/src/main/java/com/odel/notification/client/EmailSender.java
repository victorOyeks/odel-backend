package com.odel.notification.client;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;
import com.odel.notification.service.jobs.*;

import com.odel.common.OdelProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends through Brevo: its transactional API when ODEL_BREVO_API_KEY is set, otherwise the SMTP relay
 * (same order as hospio-backend). With odel.mail.mode=log (development) it only logs; the message stays
 * readable in outbound_messages and at GET /api/v1/dev/emails.
 */
@Component
public class EmailSender {
    private final JavaMailSender mail;
    private final OdelProperties.Mail cfg;

    private final boolean logOnly;

    public EmailSender(JavaMailSender mail, OdelProperties props,
                       @org.springframework.beans.factory.annotation.Value("${odel.mail.mode:smtp}") String mode) {
        this.mail = mail;
        this.cfg = props.mail();
        this.logOnly = "log".equals(mode);
    }

    public void send(String to, String fromName, String subject, String text, String html) throws Exception {
        send(to, fromName, subject, text, html, java.util.List.of());
    }

    /** A file sent with the email (the receipt PDF). */
    public record Attachment(String fileName, String contentType, String base64) {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

        public static java.util.List<Attachment> fromJson(String json) throws Exception {
            if (json == null || json.isBlank()) return java.util.List.of();
            return java.util.List.of(JSON.readValue(json, Attachment[].class));
        }
    }

    public void send(String to, String fromName, String subject, String text, String html, java.util.List<Attachment> attachments) throws Exception {
        if (cfg.brevoApiKey() != null && !cfg.brevoApiKey().isBlank() && !logOnly) {
            sendWithBrevoApi(to, fromName, subject, text, html, attachments);
            return;
        }
        if (logOnly) {
            org.slf4j.LoggerFactory.getLogger(EmailSender.class).info("Email (dev, not sent) to {}: {}{}\n{}", to, subject,
                    attachments.isEmpty() ? "" : " (+" + attachments.size() + " attachment(s))", text);
            return;
        }
        MimeMessage msg = mail.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, html != null || !attachments.isEmpty(), "UTF-8");
        h.setFrom(new InternetAddress(cfg.fromAddress(), fromName == null ? cfg.fromName() : fromName));
        h.setTo(to);
        h.setSubject(subject);
        if (html != null) h.setText(text, html); else h.setText(text);
        for (Attachment a : attachments) {
            h.addAttachment(a.fileName(), new org.springframework.core.io.ByteArrayResource(java.util.Base64.getDecoder().decode(a.base64())),
                    a.contentType() == null ? "application/octet-stream" : a.contentType());
        }
        mail.send(msg);
    }

    private final org.springframework.web.client.RestClient brevo = org.springframework.web.client.RestClient.create("https://api.brevo.com");

    private void sendWithBrevoApi(String to, String fromName, String subject, String text, String html, java.util.List<Attachment> attachments) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("sender", java.util.Map.of("name", fromName == null ? cfg.fromName() : fromName, "email", cfg.fromAddress()));
        body.put("to", java.util.List.of(java.util.Map.of("email", to)));
        body.put("subject", subject);
        body.put("textContent", text);
        if (html != null) body.put("htmlContent", html);
        if (!attachments.isEmpty()) {
            body.put("attachment", attachments.stream().map(a -> java.util.Map.of("name", a.fileName(), "content", a.base64())).toList());
        }
        brevo.post().uri("/v3/smtp/email").header("api-key", cfg.brevoApiKey())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }
}
