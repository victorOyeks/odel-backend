package com.odel.payment.service;

import com.odel.common.audit.AuditService;
import com.odel.common.tenancy.TenantContext;
import com.odel.payment.client.GatewayException;
import com.odel.payment.client.PaymentGateway;
import com.odel.payment.client.PaymentGateway.Verification;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.entity.Refund;
import com.odel.payment.repository.FeeRepository;
import com.odel.payment.repository.PaymentRepository;
import com.odel.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What Paystack tells us, applied to fees and payments (the webhook and the 30-minute re-check). Runs in the payment's university
 * (TenantContext set by the caller). Each change is one transaction with its audit entry; emails and the refund call go after commit.
 */
@Service
public class PaymentEvents {
    private static final Logger log = LoggerFactory.getLogger(PaymentEvents.class);
    private final PaymentRepository payments;
    private final FeeRepository fees;
    private final RefundRepository refunds;
    private final PaymentGateway gateway;
    private final ReceiptService receipts;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentEvents(PaymentRepository payments, FeeRepository fees, RefundRepository refunds, PaymentGateway gateway, ReceiptService receipts,
                         AuditService audit, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.fees = fees;
        this.refunds = refunds;
        this.gateway = gateway;
        this.receipts = receipts;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    private void record(String action, Payment p, String summary, Object oldV, Object newV) {
        audit.record(new AuditService.Entry(TenantContext.require(), AuditService.Actor.SYSTEM, action, "PAYMENT", p.getId(), summary, oldV, newV));
    }

    /** Ten working days from the day the refund starts (banks take 5 to 10). */
    static LocalDate tenWorkingDays(LocalDate from) {
        LocalDate d = from;
        int added = 0;
        while (added < 10) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) added++;
        }
        return d;
    }

    /**
     * A success Paystack confirmed (source WEBHOOK or RECHECK, with Paystack's verify answer). Marks the fee paid, or starts the
     * automatic refund of a second payment (E-07). Returns the outcome.
     */
    public FeeLedger.Outcome success(String reference, Verification v, String source) {
        List<Runnable> after = new ArrayList<>();
        FeeLedger.Outcome outcome = tx.execute(t -> {
            Payment p0 = payments.findByReference(reference).orElse(null);
            if (p0 == null) return FeeLedger.Outcome.NOT_VERIFIED;
            Fee fee = fees.lockInTenant(p0.getFeeId()).orElseThrow();
            Payment p = payments.findInTenant(p0.getId()).orElseThrow();
            Instant now = clock.instant();
            FeeLedger.Outcome o = FeeLedger.verifiedSuccess(fee, p, v, now, source);
            switch (o) {
                case PAID -> {
                    payments.save(p);
                    fees.save(fee);
                    // Anything else still open for this fee is no longer needed; if its money arrives anyway it is refunded.
                    for (Payment other : payments.findByFeeIdOrderByCreatedAtDesc(fee.getId())) {
                        if (!other.getId().equals(p.getId()) && other.getStatus().open()) {
                            other.setStatus(Payment.Status.CANCELLED);
                            other.setUpdatedAt(now);
                            payments.save(other);
                        }
                    }
                    record("PAYMENT_PAID", p, p.getStudentName() + " paid the platform fee for " + p.getFeeLabel() + " (" + p.getReference() + ", "
                            + Labels.methodShort(p) + ")", null, Map.of("reference", p.getReference(), "confirmedBy", source));
                    after.add(() -> receipts.receipt(p, fee));
                }
                case DUPLICATE -> {
                    payments.save(p);
                    Refund r = new Refund();
                    r.setId(UUID.randomUUID());
                    r.setPaymentId(p.getId());
                    r.setReference(uniqueRefundReference());
                    r.setAmountKobo(p.getAmountKobo());
                    r.setReason("You paid the platform fee twice");
                    r.setStatus(Refund.Status.NOT_SENT);
                    r.setStartedAt(now);
                    r.setExpectedBy(tenWorkingDays(LocalDate.ofInstant(now, Labels.LAGOS)));
                    refunds.save(r);
                    Payment kept = payments.findInTenant(fee.getPaidPaymentId()).orElse(null);
                    record("PAYMENT_DUPLICATE_REFUND_STARTED", p, p.getStudentName() + " paid " + p.getFeeLabel() + " twice: " + p.getReference()
                            + " is being refunded (" + r.getReference() + "); " + (kept == null ? "" : kept.getReference()) + " is kept", null,
                            Map.of("refund", r.getReference(), "kept", kept == null ? "" : kept.getReference()));
                    after.add(() -> sendRefund(r.getId()));
                    after.add(() -> receipts.duplicate(p, kept, r));
                }
                default -> { }
            }
            return o;
        });
        runAll(after);
        return outcome;
    }

    private String uniqueRefundReference() {
        for (int i = 0; i < 20; i++) {
            String r = References.refund();
            if (refunds.countByReferenceAnywhere(r) == 0) return r;
        }
        throw new IllegalStateException("No free refund reference");
    }

    /** Asks Paystack to refund; on failure the refund stays NOT_SENT and the re-check job tries again. */
    public void sendRefund(UUID refundId) {
        Refund r = tx.execute(t -> refunds.findById(refundId).filter(x -> TenantContext.current().equals(x.getTenantId())).orElse(null));
        if (r == null || r.getStatus() != Refund.Status.NOT_SENT) return;
        Payment p = tx.execute(t -> payments.findInTenant(r.getPaymentId()).orElseThrow());
        try {
            PaymentGateway.RefundStarted started = gateway.refund(p.getReference(), r.getAmountKobo(), r.getReason() + " (" + r.getReference() + ")");
            tx.executeWithoutResult(t -> {
                Refund x = refunds.findById(refundId).orElseThrow();
                if (x.getStatus() != Refund.Status.NOT_SENT) return;
                x.setGatewayRefundId(started.refundId());
                x.setStatus(Refund.Status.PENDING);
                x.setLastError(null);
                if (started.expectedAt() != null) x.setExpectedBy(LocalDate.ofInstant(started.expectedAt(), Labels.LAGOS));
                refunds.save(x);
            });
        } catch (GatewayException e) {
            log.warn("Refund {} not sent yet: {}", r.getReference(), e.getMessage());
            tx.executeWithoutResult(t -> {
                Refund x = refunds.findById(refundId).orElseThrow();
                x.setLastError(e.getMessage() == null ? "Paystack refused" : e.getMessage().substring(0, Math.min(290, e.getMessage().length())));
                refunds.save(x);
            });
        }
    }

    /** refund.processed: the money reached the customer. */
    public void refundProcessed(String reference, String gatewayRefundId) {
        List<Runnable> after = new ArrayList<>();
        tx.executeWithoutResult(t -> {
            Payment p0 = payments.findByReference(reference).orElse(null);
            if (p0 == null) return;
            Fee fee = fees.lockInTenant(p0.getFeeId()).orElseThrow();
            Payment p = payments.findInTenant(p0.getId()).orElseThrow();
            if (p.getStatus() == Payment.Status.REFUNDED) return;
            Instant now = clock.instant();
            Refund r = refunds.findByPaymentId(p.getId()).orElseGet(() -> {
                // Refunded from the Paystack dashboard: recorded so the student and the office see it.
                Refund x = new Refund();
                x.setId(UUID.randomUUID());
                x.setPaymentId(p.getId());
                x.setReference(uniqueRefundReference());
                x.setAmountKobo(p.getAmountKobo());
                x.setReason("Refunded by the provider");
                x.setStartedAt(now);
                x.setExpectedBy(LocalDate.ofInstant(now, Labels.LAGOS));
                return x;
            });
            if (gatewayRefundId != null && r.getGatewayRefundId() == null) r.setGatewayRefundId(gatewayRefundId);
            boolean feeRefunded = FeeLedger.refundProcessed(fee, p, r, now);
            refunds.save(r);
            payments.save(p);
            fees.save(fee);
            record("PAYMENT_REFUNDED", p, "Refund " + r.getReference() + " of " + Money.label(r.getAmountKobo()) + " for " + p.getReference()
                    + " reached " + p.getStudentName() + (feeRefunded ? "; the fee for " + p.getFeeLabel() + " is no longer paid" : ""), null,
                    Map.of("refund", r.getReference(), "feeRefunded", feeRefunded));
            after.add(() -> receipts.refunded(p, r, feeRefunded));
        });
        runAll(after);
    }

    public void refundFailed(String reference, String message) {
        tx.executeWithoutResult(t -> payments.findByReference(reference).flatMap(p -> refunds.findByPaymentId(p.getId())).ifPresent(r -> {
            r.setStatus(Refund.Status.FAILED);
            r.setLastError(message);
            refunds.save(r);
            Payment p = payments.findInTenant(r.getPaymentId()).orElseThrow();
            record("PAYMENT_REFUND_FAILED", p, "Paystack could not send refund " + r.getReference() + " for " + p.getReference()
                    + ": " + (message == null ? "no reason given" : message), null, null);
        }));
    }

    public void disputed(String reference) {
        tx.executeWithoutResult(t -> payments.findByReference(reference).ifPresent(p -> {
            if (p.getDisputedAt() != null) return;
            p.setDisputedAt(clock.instant());
            payments.save(p);
            record("PAYMENT_DISPUTED", p, p.getStudentName() + "'s bank opened a dispute on " + p.getReference(), null, null);
        }));
    }

    /** The bank took the money back (E-08): a paid fee becomes REVERSED and registration and exams lock again. */
    public void reversed(String reference) {
        List<Runnable> after = new ArrayList<>();
        tx.executeWithoutResult(t -> {
            Payment p0 = payments.findByReference(reference).orElse(null);
            if (p0 == null) return;
            Fee fee = fees.lockInTenant(p0.getFeeId()).orElseThrow();
            Payment p = payments.findInTenant(p0.getId()).orElseThrow();
            if (p.getStatus() == Payment.Status.REVERSED) return;
            boolean feeReversed = FeeLedger.reversed(fee, p, clock.instant());
            payments.save(p);
            fees.save(fee);
            record("PAYMENT_REVERSED", p, p.getStudentName() + "'s bank took back " + p.getReference()
                    + (feeReversed ? "; the fee for " + p.getFeeLabel() + " is not paid any more (registration and exams locked)" : ""), null,
                    Map.of("feeReversed", feeReversed));
            if (feeReversed) after.add(() -> receipts.reversed(p));
        });
        runAll(after);
    }

    private static void runAll(List<Runnable> after) {
        for (Runnable r : after) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("After-payment step failed: {}", e.getMessage(), e);
            }
        }
    }
}
