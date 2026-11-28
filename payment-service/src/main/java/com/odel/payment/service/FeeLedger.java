package com.odel.payment.service;

import com.odel.payment.client.PaymentGateway.Verification;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.entity.Refund;

import java.time.Instant;

/**
 * The rules for a fee and its payments, with no I/O (unit-tested). Callers load and lock the rows, call these, then save and send.
 * The one way to PAID is {@link #verifiedSuccess}: a success Paystack confirmed server to server (webhook + verify, or the re-check).
 */
public final class FeeLedger {
    private FeeLedger() {}

    public enum Outcome { PAID, DUPLICATE, ALREADY_DONE, NOT_VERIFIED }

    /** NOT_PAID, CONFIRMING, PAID, REFUNDED or REVERSED: CONFIRMING when the fee is not paid and a payment is waiting for the bank. */
    public static String effectiveStatus(Fee fee, Payment open) {
        if (fee.getStatus() == Fee.Status.PAID) return "PAID";
        if (open != null && open.getStatus() == Payment.Status.CONFIRMING) return "CONFIRMING";
        return fee.getStatus().name();
    }

    /** Whether Paystack's verification really says this payment's money arrived: status success, the same amount, in naira. */
    public static boolean matches(Payment p, Verification v) {
        return v != null && v.success() && v.amountKobo() == p.getAmountKobo() && "NGN".equalsIgnoreCase(v.currency() == null ? "NGN" : v.currency());
    }

    /**
     * A verified success. The fee becomes PAID by this payment; or, when another payment already paid it, this one is a duplicate
     * that must be refunded (REFUNDING). A payment already settled is left alone (the same news twice changes nothing).
     */
    public static Outcome verifiedSuccess(Fee fee, Payment p, Verification v, Instant now, String source) {
        if (Payment.MONEY_RECEIVED.contains(p.getStatus())) return Outcome.ALREADY_DONE;
        if (!matches(p, v)) return Outcome.NOT_VERIFIED;
        p.setChannel(v.channel() == null ? p.getChannel() : v.channel());
        p.setCardType(v.cardType());
        p.setCardBank(v.cardBank());
        p.setCardLast4(v.cardLast4());
        p.setGatewayFeeKobo(v.feesKobo());
        p.setGatewayTransactionId(v.transactionId());
        p.setGatewayMessage(v.gatewayResponse());
        p.setPaidAt(v.paidAt() == null ? now : v.paidAt());
        p.setConfirmedBy(source);
        p.setFailureReason(null);
        p.setUpdatedAt(now);
        if (fee.getStatus() == Fee.Status.PAID && fee.getPaidPaymentId() != null && !fee.getPaidPaymentId().equals(p.getId())) {
            p.setStatus(Payment.Status.REFUNDING);
            p.setDuplicate(true);
            return Outcome.DUPLICATE;
        }
        p.setStatus(Payment.Status.PAID);
        fee.setStatus(Fee.Status.PAID);
        fee.setPaidPaymentId(p.getId());
        fee.setPaidAt(p.getPaidAt());
        fee.setUpdatedAt(now);
        return Outcome.PAID;
    }

    /** The browser came back or the student asked: show Paystack's answer, but never mark paid. Returns the message for the screen. */
    public static String checked(Payment p, Verification v, Instant now) {
        p.setLastCheckedAt(now);
        if (Payment.MONEY_RECEIVED.contains(p.getStatus())) return null;
        if (v.success()) {
            if (p.getStatus().open()) {
                p.setStatus(Payment.Status.CONFIRMING);
                if (p.getClaimedAt() == null) p.setClaimedAt(now);
                p.setUpdatedAt(now);
            }
            return "We are confirming your payment with Paystack. This usually takes a minute. Do not pay again.";
        }
        if (v.failed()) {
            if (p.getStatus().open()) {
                p.setStatus(Payment.Status.FAILED);
                p.setGatewayMessage(v.gatewayResponse());
                p.setFailureReason(Labels.failure(v.gatewayResponse()));
                p.setUpdatedAt(now);
            }
            return p.getFailureReason();
        }
        if ("abandoned".equals(v.status()) && p.getMethod() == Payment.Method.CARD && p.getStatus() == Payment.Status.PENDING) {
            return "You closed the payment page before paying. Nothing was charged. Pay again when you are ready.";
        }
        if (p.getStatus() == Payment.Status.CONFIRMING) return "We are waiting for your bank to confirm your payment. Do not pay again.";
        return null;
    }

    /** The student says the transfer was made or the USSD code dialled (P-04, P-04b). */
    public static void claimed(Payment p, Instant now) {
        if (p.getStatus() != Payment.Status.PENDING) return;
        p.setStatus(Payment.Status.CONFIRMING);
        p.setClaimedAt(now);
        p.setUpdatedAt(now);
    }

    /** Paystack sent the refund back to the customer. True when the fee itself lost its payment (it becomes REFUNDED). */
    public static boolean refundProcessed(Fee fee, Payment p, Refund r, Instant now) {
        if (r != null) {
            r.setStatus(Refund.Status.PROCESSED);
            if (r.getProcessedAt() == null) r.setProcessedAt(now);
        }
        if (p.getStatus() == Payment.Status.REFUNDED) return false;
        p.setStatus(Payment.Status.REFUNDED);
        p.setUpdatedAt(now);
        if (fee.getStatus() == Fee.Status.PAID && p.getId().equals(fee.getPaidPaymentId())) {
            fee.setStatus(Fee.Status.REFUNDED);
            fee.setUpdatedAt(now);
            return true;
        }
        return false;
    }

    /** The bank took the money back (a dispute the customer won). True when the fee was paid by it: REVERSED, locked again (E-08). */
    public static boolean reversed(Fee fee, Payment p, Instant now) {
        if (p.getStatus() == Payment.Status.REVERSED) return false;
        p.setStatus(Payment.Status.REVERSED);
        p.setReversedAt(now);
        p.setUpdatedAt(now);
        if (fee.getStatus() == Fee.Status.PAID && p.getId().equals(fee.getPaidPaymentId())) {
            fee.setStatus(Fee.Status.REVERSED);
            fee.setUpdatedAt(now);
            return true;
        }
        return false;
    }

    /** The re-check found nothing paid and the payment's time is up. */
    public static void expired(Payment p, Instant now) {
        if (!p.getStatus().open()) return;
        p.setStatus(p.getMethod() == Payment.Method.CARD ? Payment.Status.CANCELLED : Payment.Status.EXPIRED);
        p.setUpdatedAt(now);
    }
}
