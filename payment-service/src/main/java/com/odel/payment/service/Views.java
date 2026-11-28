package com.odel.payment.service;

import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.entity.Refund;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Entities → the screens' views. Pure; the split never appears here (university and student views). */
public final class Views {
    private Views() {}

    static final List<MethodOption> METHODS = List.of(new MethodOption("CARD", "Debit card"),
            new MethodOption("BANK_TRANSFER", "Bank transfer"), new MethodOption("USSD", "USSD"));

    public static String receiptUrl(Payment p) {
        return "/api/v1/payments/" + p.getId() + "/receipt.pdf";
    }

    static String refundTo(Payment p) {
        if (p.getCardLast4() != null) return (p.getCardBank() == null ? Labels.cardBrand(p.getCardType()) : p.getCardBank()) + " card ending " + p.getCardLast4();
        if ("bank_transfer".equals(p.getChannel())) return "the bank account you paid from";
        return "the account you paid from";
    }

    public static RefundView refund(Payment p, Refund r) {
        if (r == null) return null;
        return new RefundView(r.getReference(), Money.naira(r.getAmountKobo()), Money.label(r.getAmountKobo()),
                r.getStatus() == Refund.Status.NOT_SENT ? "PENDING" : r.getStatus().name(), r.getStartedAt(), r.getProcessedAt(),
                r.getExpectedBy(), refundTo(p), r.getReason());
    }

    static String message(Payment p) {
        return switch (p.getStatus()) {
            case PENDING -> switch (p.getMethod()) {
                case CARD -> "Finish paying on the Paystack page. If you closed it, open it again from here.";
                case USSD -> "Dial the code from the phone number linked to your bank account, then tap \"I have dialled the code\".";
                case BANK_TRANSFER -> "Send exactly " + Money.exact(p.getAmountKobo()) + " to the account below in one transfer, then tap \"I have made the transfer\".";
            };
            case CONFIRMING -> "We are waiting for your bank to confirm your payment. Do not pay again.";
            case PAID -> "Payment received. Your receipt is ready.";
            case FAILED -> p.getFailureReason() == null ? Labels.failure(p.getGatewayMessage()) : p.getFailureReason();
            case CANCELLED -> "This payment was not completed. Nothing was charged.";
            case EXPIRED -> "Time ran out before any money arrived. Nothing was charged. Start a new payment.";
            case REFUNDING -> "You paid twice. This payment is going back to " + refundTo(p) + ".";
            case REFUNDED -> "This payment was refunded to " + refundTo(p) + ".";
            case REVERSED -> "Your bank took this payment back.";
        };
    }

    /** What the student pays for this payment: Paystack's figure when known (transfer account, verified payment), else the estimate. */
    static long toPay(Payment p) {
        return p.getChargedKobo() != null ? p.getChargedKobo() : GatewayCharge.estimatedTotal(p.getAmountKobo());
    }

    /** Before paying: the fee, Paystack's estimated charge and the estimated total. */
    public static ChargeView estimate(long feeKobo) {
        long total = GatewayCharge.estimatedTotal(feeKobo);
        long charge = total - feeKobo;
        return new ChargeView(GatewayCharge.studentPays(), Money.naira(feeKobo), Money.naira(charge), Money.naira(total), Money.label(feeKobo),
                Money.exact(charge), Money.exact(total), true, charge == 0 ? null
                : "Paystack adds its charge of about " + Money.exact(charge) + ", so you pay about " + Money.exact(total)
                  + ". Paystack shows the exact amount before you confirm.");
    }

    /** For one payment: Paystack's real figures once known, the estimate before. */
    static ChargeView charge(Payment p) {
        if (p.getChargedKobo() == null) return estimate(p.getAmountKobo());
        long total = p.getChargedKobo();
        long charge = Math.max(0, total - p.getAmountKobo());
        boolean paid = p.getPaidAt() != null;
        return new ChargeView(charge > 0, Money.naira(p.getAmountKobo()), Money.naira(charge), Money.naira(total), Money.label(p.getAmountKobo()),
                Money.exact(charge), Money.exact(total), false, charge == 0 ? null
                : (paid ? "You paid " : "You pay ") + Money.exact(total) + ": the " + Money.label(p.getAmountKobo()) + " fee and Paystack's " + Money.exact(charge) + " charge.");
    }

    public static PaymentView payment(Payment p, Refund r) {
        boolean open = p.getStatus().open();
        CheckoutView checkout = p.getMethod() == Payment.Method.CARD && open && p.getAuthorizationUrl() != null ? new CheckoutView(p.getAuthorizationUrl()) : null;
        UssdView ussd = p.getMethod() == Payment.Method.USSD && open ? new UssdView(UssdBanks.name(p.getUssdBank()), p.getUssdCode(), p.getExpiresAt()) : null;
        TransferView transfer = p.getMethod() == Payment.Method.BANK_TRANSFER && open && p.getTransferAccountNumber() != null
                ? new TransferView(p.getTransferBank(), p.getTransferAccountNumber(), p.getTransferAccountName(), Money.naira(toPay(p)),
                Money.exact(toPay(p)), p.getExpiresAt(),
                "Send exactly " + Money.exact(toPay(p)) + " in one transfer" + (toPay(p) > p.getAmountKobo()
                        ? " (the " + Money.exact(p.getAmountKobo()) + " fee plus Paystack's " + Money.exact(toPay(p) - p.getAmountKobo()) + " charge)" : "")
                        + ". This account works for 30 minutes.") : null;
        boolean receipt = p.getStatus() == Payment.Status.PAID || (p.getPaidAt() != null && !p.isDuplicate());
        return new PaymentView(p.getId(), p.getFeeId(), p.getFeeLabel(), p.getReference(), Labels.methodKey(p), Labels.method(p),
                Money.naira(p.getAmountKobo()), Money.label(p.getAmountKobo()), p.getStatus().name(), Labels.paymentStatus(p.getStatus()),
                message(p), p.getCreatedAt(), p.getPaidAt(), p.getExpiresAt(), checkout, ussd, transfer,
                p.getStatus() == Payment.Status.FAILED ? message(p) : null, receipt ? receiptUrl(p) : null, refund(p, r), p.isDuplicate(), charge(p));
    }

    static String feeMessage(String status, Fee fee, Payment paid, Payment reversed) {
        return switch (status) {
            case "PAID" -> "Paid on " + Labels.day(fee.getPaidAt()) + ". Registration is open to you.";
            case "CONFIRMING" -> "We are waiting for your bank to confirm your payment. Do not pay again.";
            case "REFUNDED" -> "Your payment was refunded. Pay again to register your courses and sit exams.";
            case "REVERSED" -> "Your bank took back the " + Money.label(fee.getAmountKobo()) + " you paid"
                    + (reversed == null || reversed.getPaidAt() == null ? "" : " on " + Labels.shortDay(reversed.getPaidAt()))
                    + ". Registration and exams are locked. Pay again or call the ODeL office.";
            default -> "Pay your platform fee to register your courses and sit exams.";
        };
    }

    /** One fee with its payments (newest first) and their refunds. */
    public static FeeView fee(Fee fee, List<Payment> payments, Map<UUID, Refund> refunds) {
        List<Payment> sorted = new ArrayList<>(payments);
        sorted.sort(Comparator.comparing(Payment::getCreatedAt).reversed());
        Payment open = sorted.stream().filter(p -> p.getStatus().open()).findFirst().orElse(null);
        Payment paid = fee.getPaidPaymentId() == null ? null : sorted.stream().filter(p -> p.getId().equals(fee.getPaidPaymentId())).findFirst().orElse(null);
        String status = FeeLedger.effectiveStatus(fee, open);
        boolean isPaid = "PAID".equals(status);
        Payment dup = isPaid ? sorted.stream().filter(Payment::isDuplicate).findFirst().orElse(null) : null;
        Payment rev = "REVERSED".equals(status) ? sorted.stream().filter(p -> p.getStatus() == Payment.Status.REVERSED).findFirst().orElse(null) : null;

        List<String> actions = new ArrayList<>();
        if (!isPaid && !"CONFIRMING".equals(status)) actions.add("PAY");
        if (open != null) actions.add("CHECK_STATUS");
        if (isPaid) actions.add("DOWNLOAD_RECEIPT");
        if (!sorted.isEmpty()) actions.add("VIEW_PAYMENTS");

        return new FeeView(fee.getId(), fee.getSemesterId(), fee.getSessionId(), fee.getSessionName(), fee.getSemesterNumber(),
                Fee.semesterName(fee.getSemesterNumber()), fee.label(), Money.naira(fee.getAmountKobo()), Money.label(fee.getAmountKobo()),
                status, Labels.feeStatus(status), feeMessage(status, fee, paid, rev), !isPaid,
                open == null || isPaid ? null : payment(open, refunds.get(open.getId())),
                isPaid && paid != null ? new PaidRef(paid.getId(), paid.getReference(), paid.getPaidAt(), Labels.methodKey(paid), Labels.method(paid), receiptUrl(paid)) : null,
                dup == null ? null : new Duplicate(dup.getId(), dup.getReference(), dup.getPaidAt(), Money.naira(dup.getAmountKobo()),
                        Money.label(dup.getAmountKobo()), refund(dup, refunds.get(dup.getId()))),
                rev == null ? null : new Reversal(rev.getId(), rev.getReference(), rev.getReversedAt(), feeMessage(status, fee, paid, rev)),
                METHODS, UssdBanks.ALL, UssdBanks.OTHERS, actions, estimate(fee.getAmountKobo()));
    }
}
