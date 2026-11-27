package com.odel.tenant.service;

import com.odel.tenant.entity.Tenant;
import com.odel.tenant.repository.TenantRepository;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.audit.AuditService;
import com.odel.common.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A university's own domain (e.g. odl.wesleyuni.edu.ng). Its IT team adds a CNAME to the university's platform address;
 * this checks every 5 minutes whether the domain now reaches our server (DNS_FOUND). The server's
 * odel-custom-domains job then issues the certificate and turns the nginx site on, and reports back (LIVE or an error).
 */
@Service
public class CustomDomainService {
    private static final Logger log = LoggerFactory.getLogger(CustomDomainService.class);
    /** A failed certificate attempt is retried after this long. */
    private static final Duration CERT_RETRY = Duration.ofMinutes(30);

    private final TenantRepository tenants;
    private final TenantSetupService setup;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final OdelProperties props;
    private final Clock clock;

    public CustomDomainService(TenantRepository tenants, TenantSetupService setup, AuditService audit,
                               TransactionTemplate tx, OdelProperties props, Clock clock) {
        this.tenants = tenants;
        this.setup = setup;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /** Every 5 minutes: every domain still waiting for its DNS record. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void checkWaiting() {
        if ("localhost".equals(props.baseDomain())) return;
        List<UUID> waiting = tx.execute(s -> tenants.findAll().stream()
                .filter(t -> t.getWebAddressStatus() == Tenant.WebAddressStatus.WAITING_DNS).map(Tenant::getId).toList());
        if (waiting != null) waiting.forEach(id -> {
            try { check(id, null); } catch (RuntimeException e) { log.warn("Domain check for {} failed: {}", id, e.getMessage()); }
        });
    }

    /** Looks the domain up now. actor is null for the scheduled check. */
    public Tenant check(UUID tenantId, AuditService.Actor actor) {
        return tx.execute(s -> {
            Tenant t = tenants.findById(tenantId).filter(x -> !x.isPlatform()).orElseThrow(ApiException::notFound);
            if (t.getWebAddress() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "no_domain", t.getName() + " has no own domain to check.");
            }
            if (t.getWebAddressStatus() == Tenant.WebAddressStatus.LIVE) return t;
            String domain = t.getWebAddress();
            String platformHost = setup.platformUrl(t).replaceFirst("^https?://", "").replaceFirst(":\\d+$", "");
            t.setWebAddressCheckedAt(clock.instant());
            Set<String> ours = addresses(platformHost);
            Set<String> theirs;
            try {
                theirs = addresses(domain);
            } catch (IllegalStateException e) {
                t.setWebAddressStatus(Tenant.WebAddressStatus.WAITING_DNS);
                t.setWebAddressError("No DNS record for " + domain + " yet. Add a CNAME record pointing to " + platformHost + ".");
                return t;
            }
            if (theirs.stream().anyMatch(ours::contains)) {
                boolean changed = t.getWebAddressStatus() != Tenant.WebAddressStatus.DNS_FOUND;
                t.setWebAddressStatus(Tenant.WebAddressStatus.DNS_FOUND);
                t.setWebAddressError(null);
                if (changed) {
                    audit.record(new AuditService.Entry(t.getId(), actor == null ? AuditService.Actor.SYSTEM : actor, "DOMAIN_DNS_FOUND",
                            "tenant", t.getId(), domain + " now points to the platform; issuing its certificate", null, Map.of("domain", domain)));
                }
            } else {
                t.setWebAddressStatus(Tenant.WebAddressStatus.WAITING_DNS);
                t.setWebAddressError(domain + " points to " + String.join(", ", theirs) + ", not to the platform. Change it to a CNAME pointing to "
                        + platformHost + " (in Cloudflare, set it to \"DNS only\", not proxied).");
            }
            return t;
        });
    }

    public record Pending(UUID tenantId, String domain) {}

    /** For the server's certificate job: domains that point at us and are due a (first or retried) certificate attempt. */
    public List<Pending> pending() {
        Instant retryBefore = clock.instant().minus(CERT_RETRY);
        return tx.execute(s -> tenants.findAll().stream()
                .filter(t -> t.getWebAddressStatus() == Tenant.WebAddressStatus.DNS_FOUND)
                .filter(t -> t.getWebAddressCertAttemptAt() == null || t.getWebAddressCertAttemptAt().isBefore(retryBefore))
                .map(t -> new Pending(t.getId(), t.getWebAddress())).toList());
    }

    /** The certificate job's answer: live, or what went wrong (shown in the console, retried later). */
    public void result(UUID tenantId, String domain, boolean ok, String error) {
        tx.executeWithoutResult(s -> {
            Tenant t = tenants.findById(tenantId).orElseThrow(ApiException::notFound);
            if (!domain.equalsIgnoreCase(t.getWebAddress())) return; // the domain was changed meanwhile
            Instant now = clock.instant();
            t.setWebAddressCertAttemptAt(now);
            if (ok) {
                t.setWebAddressStatus(Tenant.WebAddressStatus.LIVE);
                t.setWebAddressLiveAt(now);
                t.setWebAddressError(null);
                audit.record(new AuditService.Entry(t.getId(), AuditService.Actor.SYSTEM, "DOMAIN_LIVE", "tenant", t.getId(),
                        domain + " is live with its own certificate", null, Map.of("domain", domain)));
            } else {
                t.setWebAddressError("Certificate not issued yet: " + (error == null ? "unknown error" : error.trim()) + " We try again in 30 minutes.");
            }
        });
    }

    private static Set<String> addresses(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host)).map(InetAddress::getHostAddress).collect(Collectors.toSet());
        } catch (UnknownHostException e) {
            throw new IllegalStateException("no record");
        }
    }
}
