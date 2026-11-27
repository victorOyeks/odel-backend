package com.odel.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Dev safety net: when ODEL_MAIL_ALLOW_DOMAINS is set (e.g. "gmail.com,oyeksglobal.com"), email goes out only to those
 * domains (and their subdomains). Anything else is recorded as SKIPPED, still readable in the dev email log, so a dev
 * university with a real staff domain (wesleyuni.edu.ng) never emails real people. Empty = no restriction (production).
 */
@Component
public class MailAllowList {
    public static final String REASON = "Not sent: not on the dev allow-list";
    private final List<String> domains;

    public MailAllowList(@Value("${odel.mail.allow-domains:}") String allowDomains) {
        this.domains = Arrays.stream(allowDomains == null ? new String[0] : allowDomains.split(","))
                .map(d -> d.trim().toLowerCase(Locale.ROOT)).map(d -> d.startsWith("@") ? d.substring(1) : d)
                .filter(d -> !d.isEmpty()).toList();
    }

    public boolean restricted() {
        return !domains.isEmpty();
    }

    public boolean allows(String email) {
        if (domains.isEmpty()) return true;
        if (email == null || !email.contains("@")) return false;
        String domain = email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        return domains.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    }
}
