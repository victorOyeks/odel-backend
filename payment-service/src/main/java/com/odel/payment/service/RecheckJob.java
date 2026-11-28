package com.odel.payment.service;

import com.odel.common.tenancy.TenantContext;
import com.odel.payment.client.GatewayException;
import com.odel.payment.client.PaymentGateway;
import com.odel.payment.config.PaymentProperties;
import com.odel.payment.dto.PaymentDtos.RecheckResult;
import com.odel.payment.entity.Payment;
import com.odel.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Every minute: payments still open 30 minutes after they started are verified with Paystack (a lost webhook cannot leave a student
 * waiting), each at most every 10 minutes. Paid → the same path as the webhook; failed → FAILED; nothing after the time is up → EXPIRED
 * (transfers and USSD after their 30 minutes, checkout after a day). Refunds Paystack could not take yet are sent again.
 */
@Component
public class RecheckJob {
    private static final Logger log = LoggerFactory.getLogger(RecheckJob.class);
    static final Duration AFTER = Duration.ofMinutes(30);
    static final Duration EVERY = Duration.ofMinutes(10);
    static final Duration CHECKOUT_GIVE_UP = Duration.ofHours(24);
    static final Duration TRANSFER_GIVE_UP = Duration.ofHours(48);

    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentEvents events;
    private final PaymentProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RecheckJob(JdbcTemplate jdbc, PaymentRepository payments, PaymentGateway gateway, PaymentEvents events, PaymentProperties props,
                      TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void tick() {
        if (!props.recheckEnabled()) return;
        try {
            run(clock.instant());
        } catch (Exception e) {
            log.warn("Payment re-check failed: {}", e.getMessage(), e);
        }
    }

    record Due(UUID id, UUID tenantId, String reference) {}

    /** One pass as if the time were now (the dev endpoint passes a later time). */
    public synchronized RecheckResult run(Instant now) {
        int checked = 0, paid = 0, failed = 0, expired = 0;
        var due = jdbc.query("""
                select id, tenant_id, reference from payments where status in ('PENDING', 'CONFIRMING') and created_at <= ?
                  and (last_checked_at is null or last_checked_at <= ?) order by created_at limit 200""",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)),
                Timestamp.from(now.minus(AFTER)), Timestamp.from(now.minus(EVERY)));
        for (Due d : due) {
            checked++;
            String r = TenantContext.runAs(d.tenantId(), () -> one(d, now));
            switch (r) {
                case "paid" -> paid++;
                case "failed" -> failed++;
                case "expired" -> expired++;
                default -> { }
            }
        }
        var refunds = jdbc.query("select id, tenant_id from refunds where status = 'NOT_SENT'",
                (rs, i) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
        for (UUID[] r : refunds) TenantContext.runAs(r[1], () -> events.sendRefund(r[0]));
        if (checked > 0) log.info("Payment re-check: {} checked, {} paid, {} failed, {} expired", checked, paid, failed, expired);
        return new RecheckResult(checked, paid, failed, expired);
    }

    private String one(Due d, Instant now) {
        PaymentGateway.Verification v;
        try {
            v = gateway.verify(d.reference());
        } catch (GatewayException e) {
            log.info("Re-check of {} postponed: {}", d.reference(), e.getMessage());
            return "error";
        }
        if (v.success()) {
            FeeLedger.Outcome o = events.success(d.reference(), v, "RECHECK");
            return o == FeeLedger.Outcome.PAID || o == FeeLedger.Outcome.DUPLICATE ? "paid" : "checked";
        }
        return tx.execute(t -> {
            Payment p = payments.findInTenant(d.id()).orElseThrow();
            if (!p.getStatus().open()) return "checked";
            FeeLedger.checked(p, v, now);
            p.setLastCheckedAt(now);
            String out = p.getStatus() == Payment.Status.FAILED ? "failed" : "checked";
            if (p.getStatus().open()) {
                boolean card = p.getMethod() == Payment.Method.CARD;
                Instant deadline = card ? p.getCreatedAt().plus(CHECKOUT_GIVE_UP)
                        : "pending".equals(v.status()) || "processing".equals(v.status()) || "ongoing".equals(v.status())
                          ? p.getCreatedAt().plus(TRANSFER_GIVE_UP)
                          : (p.getExpiresAt() == null ? p.getCreatedAt().plus(AFTER) : p.getExpiresAt());
                if (!now.isBefore(deadline) && !"success".equals(v.status())) {
                    FeeLedger.expired(p, now);
                    out = "expired";
                }
            }
            payments.save(p);
            return out;
        });
    }
}
