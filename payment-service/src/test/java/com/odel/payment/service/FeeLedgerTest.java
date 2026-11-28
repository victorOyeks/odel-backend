package com.odel.payment.service;

import com.odel.payment.client.PaymentGateway.Verification;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.entity.Refund;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeeLedgerTest {
    final Instant now = Instant.parse("2026-12-04T13:28:00Z");

    static Fee fee() {
        Fee f = new Fee();
        f.setId(UUID.randomUUID());
        f.setStatus(Fee.Status.NOT_PAID);
        f.setAmountKobo(1_500_000);
        f.setSessionName("2026/2027");
        f.setSemesterNumber(1);
        return f;
    }

    static Payment payment(Fee f, Payment.Method m) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setFeeId(f.getId());
        p.setMethod(m);
        p.setStatus(Payment.Status.PENDING);
        p.setAmountKobo(f.getAmountKobo());
        p.setReference("WU-PF-" + (100000 + (int) (Math.random() * 900000)));
        p.setCreatedAt(Instant.parse("2026-12-04T13:20:00Z"));
        return p;
    }

    static Verification ok(long amount) {
        return new Verification("success", amount, "NGN", "card", "Successful", Instant.parse("2026-12-04T13:28:00Z"), 32_500L,
                "4099260516", "visa DEBIT", "GTBank", "4412");
    }

    static Verification status(String s, String response) {
        return new Verification(s, 1_500_000, "NGN", "card", response, null, null, null, null, null, null);
    }

    @Test
    void label() {
        assertThat(fee().label()).isEqualTo("2026/2027 · 1st semester");
    }

    @Test
    void confirmingThenPaidOnlyByAVerifiedSuccess() {
        Fee f = fee();
        Payment p = payment(f, Payment.Method.BANK_TRANSFER);
        assertThat(FeeLedger.effectiveStatus(f, p)).isEqualTo("NOT_PAID");
        FeeLedger.claimed(p, now);
        assertThat(p.getStatus()).isEqualTo(Payment.Status.CONFIRMING);
        assertThat(FeeLedger.effectiveStatus(f, p)).isEqualTo("CONFIRMING");

        // Wrong amount or not success: not verified, nothing changes.
        assertThat(FeeLedger.verifiedSuccess(f, p, ok(1_000_000), now, "WEBHOOK")).isEqualTo(FeeLedger.Outcome.NOT_VERIFIED);
        assertThat(FeeLedger.verifiedSuccess(f, p, status("pending", null), now, "WEBHOOK")).isEqualTo(FeeLedger.Outcome.NOT_VERIFIED);
        assertThat(f.getStatus()).isEqualTo(Fee.Status.NOT_PAID);

        assertThat(FeeLedger.verifiedSuccess(f, p, ok(1_500_000), now, "WEBHOOK")).isEqualTo(FeeLedger.Outcome.PAID);
        assertThat(f.getStatus()).isEqualTo(Fee.Status.PAID);
        assertThat(f.getPaidPaymentId()).isEqualTo(p.getId());
        assertThat(p.getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(p.getCardLast4()).isEqualTo("4412");
        assertThat(p.getGatewayFeeKobo()).isEqualTo(32_500);
        assertThat(FeeLedger.effectiveStatus(f, null)).isEqualTo("PAID");
        // The same news again changes nothing.
        assertThat(FeeLedger.verifiedSuccess(f, p, ok(1_500_000), now, "RECHECK")).isEqualTo(FeeLedger.Outcome.ALREADY_DONE);
        assertThat(p.getConfirmedBy()).isEqualTo("WEBHOOK");
    }

    @Test
    void theBrowserNeverMarksPaid() {
        Fee f = fee();
        Payment p = payment(f, Payment.Method.CARD);
        String msg = FeeLedger.checked(p, ok(1_500_000), now);
        assertThat(p.getStatus()).isEqualTo(Payment.Status.CONFIRMING);
        assertThat(f.getStatus()).isEqualTo(Fee.Status.NOT_PAID);
        assertThat(msg).contains("Do not pay again");
    }

    @Test
    void declinedAndClosed() {
        Fee f = fee();
        Payment closed = payment(f, Payment.Method.CARD);
        assertThat(FeeLedger.checked(closed, status("abandoned", null), now)).contains("closed the payment page");
        assertThat(closed.getStatus()).isEqualTo(Payment.Status.PENDING);
        Payment declined = payment(f, Payment.Method.CARD);
        assertThat(FeeLedger.checked(declined, status("failed", "Insufficient Funds"), now)).contains("not enough money");
        assertThat(declined.getStatus()).isEqualTo(Payment.Status.FAILED);
    }

    @Test
    void aSecondPaymentIsADuplicateToRefund() {
        Fee f = fee();
        Payment first = payment(f, Payment.Method.CARD);
        Payment second = payment(f, Payment.Method.BANK_TRANSFER);
        second.setStatus(Payment.Status.CANCELLED); // replaced by the card payment, but the transfer arrived anyway
        assertThat(FeeLedger.verifiedSuccess(f, first, ok(1_500_000), now, "WEBHOOK")).isEqualTo(FeeLedger.Outcome.PAID);
        assertThat(FeeLedger.verifiedSuccess(f, second, ok(1_500_000), now, "WEBHOOK")).isEqualTo(FeeLedger.Outcome.DUPLICATE);
        assertThat(second.getStatus()).isEqualTo(Payment.Status.REFUNDING);
        assertThat(second.isDuplicate()).isTrue();
        assertThat(f.getPaidPaymentId()).isEqualTo(first.getId());

        Refund r = new Refund();
        assertThat(FeeLedger.refundProcessed(f, second, r, now)).as("the fee stays paid").isFalse();
        assertThat(second.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(r.getStatus()).isEqualTo(Refund.Status.PROCESSED);
        assertThat(f.getStatus()).isEqualTo(Fee.Status.PAID);
        assertThat(FeeLedger.refundProcessed(f, second, r, now)).as("twice changes nothing").isFalse();
    }

    @Test
    void refundingThePayingPaymentUnpaysTheFee() {
        Fee f = fee();
        Payment p = payment(f, Payment.Method.CARD);
        FeeLedger.verifiedSuccess(f, p, ok(1_500_000), now, "WEBHOOK");
        assertThat(FeeLedger.refundProcessed(f, p, null, now)).isTrue();
        assertThat(f.getStatus()).isEqualTo(Fee.Status.REFUNDED);
        assertThat(FeeLedger.effectiveStatus(f, null)).isEqualTo("REFUNDED");
    }

    @Test
    void aReversalRelocks() {
        Fee f = fee();
        Payment p = payment(f, Payment.Method.BANK_TRANSFER);
        FeeLedger.verifiedSuccess(f, p, ok(1_500_000), now, "WEBHOOK");
        assertThat(FeeLedger.reversed(f, p, now)).isTrue();
        assertThat(f.getStatus()).isEqualTo(Fee.Status.REVERSED);
        assertThat(p.getStatus()).isEqualTo(Payment.Status.REVERSED);
        assertThat(FeeLedger.reversed(f, p, now)).as("twice changes nothing").isFalse();
        // Paying again: a new payment can pay the fee.
        Payment again = payment(f, Payment.Method.CARD);
        assertThat(FeeLedger.verifiedSuccess(f, again, ok(1_500_000), now, "WEBHOOK")).isEqualTo(FeeLedger.Outcome.PAID);
        assertThat(f.getStatus()).isEqualTo(Fee.Status.PAID);
    }

    @Test
    void expiry() {
        Fee f = fee();
        Payment t = payment(f, Payment.Method.BANK_TRANSFER);
        FeeLedger.expired(t, now);
        assertThat(t.getStatus()).isEqualTo(Payment.Status.EXPIRED);
        Payment c = payment(f, Payment.Method.CARD);
        FeeLedger.expired(c, now);
        assertThat(c.getStatus()).isEqualTo(Payment.Status.CANCELLED);
    }

    @Test
    void tenWorkingDays() {
        // Fri 4 Dec 2026 → Fri 18 Dec 2026 (the story's "in the account by 18 Dec").
        assertThat(PaymentEvents.tenWorkingDays(java.time.LocalDate.of(2026, 12, 4))).isEqualTo(java.time.LocalDate.of(2026, 12, 18));
    }
}
