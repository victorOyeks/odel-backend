package com.odel.messaging;

import com.odel.common.OdelProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends through SMTP (any provider). With odel.mail.mode=log (development) it only logs; the message
 * stays readable in outbound_messages and at GET /api/v1/dev/emails.
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
        if (logOnly) {
            org.slf4j.LoggerFactory.getLogger(EmailSender.class).info("Email (dev, not sent) to {}: {}\n{}", to, subject, text);
            return;
        }
        MimeMessage msg = mail.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, html != null, "UTF-8");
        h.setFrom(new InternetAddress(cfg.fromAddress(), fromName == null ? cfg.fromName() : fromName));
        h.setTo(to);
        h.setSubject(subject);
        if (html != null) h.setText(text, html); else h.setText(text);
        mail.send(msg);
    }
}
