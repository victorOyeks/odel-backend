package com.odel.payment.service;

import com.odel.payment.entity.Payment;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Words the screens and emails show for statuses, methods and dates (Africa/Lagos). */
public final class Labels {
    private Labels() {}

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK).withZone(LAGOS);
    static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(LAGOS);
    static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.UK).withZone(LAGOS);

    public static String day(Instant t) { return t == null ? "" : DAY.format(t); }

    public static String shortDay(Instant t) { return t == null ? "" : SHORT_DAY.format(t); }

    public static String dayTime(Instant t) { return t == null ? "" : DAY_TIME.format(t); }

    public static String feeStatus(String status) {
        return switch (status) {
            case "PAID" -> "Paid";
            case "CONFIRMING" -> "Confirming";
            case "REFUNDED" -> "Refunded";
            case "REVERSED" -> "Reversed";
            case "FAILED" -> "Failed";
            default -> "Not paid";
        };
    }

    public static String paymentStatus(Payment.Status s) {
        return switch (s) {
            case PENDING -> "Waiting for you";
            case CONFIRMING -> "Confirming";
            case PAID -> "Paid";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            case EXPIRED -> "Expired";
            case REFUNDING -> "Refunding";
            case REFUNDED -> "Refunded";
            case REVERSED -> "Reversed";
        };
    }

    public static String requestedMethod(Payment.Method m) {
        return switch (m) {
            case CARD -> "Debit card";
            case USSD -> "USSD";
            case BANK_TRANSFER -> "Bank transfer";
        };
    }

    /** What was actually used, once the gateway says: "Debit card · GTBank card ending 4412", "Bank transfer", "USSD (GTBank)". */
    public static String method(Payment p) {
        String ch = p.getChannel();
        if (ch == null) {
            if (p.getMethod() == Payment.Method.USSD && p.getUssdBank() != null) return "USSD (" + UssdBanks.name(p.getUssdBank()) + ")";
            return requestedMethod(p.getMethod());
        }
        return switch (ch) {
            case "card" -> {
                String bank = p.getCardBank();
                String last4 = p.getCardLast4();
                if (last4 == null) yield "Debit card";
                yield "Debit card · " + (bank == null || bank.isBlank() ? cardBrand(p.getCardType()) : bank) + " card ending " + last4;
            }
            case "bank_transfer", "dedicated_nuban" -> "Bank transfer" + (p.getTransferBank() == null ? "" : " (" + p.getTransferBank() + ")");
            case "ussd" -> "USSD" + (p.getUssdBank() == null ? "" : " (" + UssdBanks.name(p.getUssdBank()) + ")");
            case "bank" -> "Bank account";
            case "qr" -> "QR code";
            case "mobile_money" -> "Mobile money";
            default -> requestedMethod(p.getMethod());
        };
    }

    /** The short form for tables (A-01): Card, Bank transfer, USSD. */
    public static String methodShort(Payment p) {
        String ch = p.getChannel();
        if (ch == null) return switch (p.getMethod()) { case CARD -> "Card"; case USSD -> "USSD"; case BANK_TRANSFER -> "Bank transfer"; };
        return switch (ch) {
            case "card" -> "Card";
            case "bank_transfer", "dedicated_nuban" -> "Bank transfer";
            case "ussd" -> "USSD";
            case "bank" -> "Bank account";
            default -> ch;
        };
    }

    public static String methodKey(Payment p) {
        String ch = p.getChannel();
        if (ch == null) return p.getMethod().name();
        return switch (ch) {
            case "card" -> "CARD";
            case "bank_transfer", "dedicated_nuban" -> "BANK_TRANSFER";
            case "ussd" -> "USSD";
            default -> ch.toUpperCase(Locale.ROOT);
        };
    }

    static String cardBrand(String type) {
        if (type == null || type.isBlank()) return "Your";
        String t = type.trim().split("\\s+")[0];
        return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1).toLowerCase(Locale.ROOT);
    }

    /** P-07: the bank's words turned into a sentence that says what to do next. */
    public static String failure(String gatewayResponse) {
        String g = gatewayResponse == null ? "" : gatewayResponse.toLowerCase(Locale.ROOT);
        if (g.contains("insufficient")) return "Your bank declined the card: not enough money in the account. Try another card or pay by transfer.";
        if (g.contains("expired")) return "The card has expired. Try another card or pay by transfer.";
        if (g.contains("pin") || g.contains("otp") || g.contains("token")) return "The card's PIN or one-time code was not right. Try again, or pay by transfer.";
        if (g.contains("declin") || g.contains("not honor") || g.contains("do not honor")) return "Your bank declined the card. Try another card or pay by transfer.";
        return "The payment did not go through. Nothing was taken from your account. Try again, or pay another way.";
    }
}
