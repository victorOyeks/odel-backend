package com.odel.notification.service;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;
import com.odel.notification.service.jobs.*;

import com.odel.common.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Message templates: the university's own version if it has one, otherwise the platform default. */
@Component
public class Templates {
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");
    private final JdbcTemplate jdbc;

    public Templates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Template(String subject, String body) {}

    public enum Channel { EMAIL, IN_APP, PUSH }

    public Optional<Template> find(UUID tenantId, String key, Channel channel) {
        return jdbc.query("""
                select subject, body from message_templates where key = ? and channel = ? and tenant_id in (?, ?)
                order by case when tenant_id = ? then 0 else 1 end limit 1""",
                (rs, i) -> new Template(rs.getString(1), rs.getString(2)),
                key, channel.name(), tenantId, TenantContext.PLATFORM_TENANT_ID, tenantId).stream().findFirst();
    }

    /** Replaces {{name}}; a placeholder with no value is an error, so a message never goes out half-filled. */
    public static String render(String text, Map<String, ?> vars) {
        if (text == null) return null;
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            if (v == null) throw new IllegalArgumentException("Template value missing: " + m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
