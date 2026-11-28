package com.odel.payment.service;

import java.util.Locale;

/**
 * Who pays Paystack's charge, and how much it is (owner decision 28 Nov 2026: the student pays it).
 *
 * <p>The charge is really added by Paystack ("pass charges to customer" in the Paystack dashboard), so the amount the
 * student actually paid always comes from Paystack (verify's {@code amount}, the transfer account's {@code amount}). This
 * class only gives the estimate shown before paying, using Paystack's local rate: 1.5% + ₦100 (the ₦100 waived under
 * ₦2,500), the charge capped at ₦2,000. With the customer paying, Paystack grosses up: total = (fee + ₦100) / 0.985,
 * so ₦15,000 becomes ₦15,329.95. Set {@code ODEL_PAYMENT_CHARGE_BEARER=PROVIDER} if the dashboard setting is ever turned off.
 */
public final class GatewayCharge {
    private GatewayCharge() {}

    static final long FLAT_KOBO = 10_000;
    static final long FLAT_WAIVED_BELOW_KOBO = 250_000;
    static final long CAP_KOBO = 200_000;
    static final double RATE = 0.015;

    public static boolean studentPays() {
        String v = System.getenv("ODEL_PAYMENT_CHARGE_BEARER");
        return v == null || v.isBlank() || !"PROVIDER".equals(v.trim().toUpperCase(Locale.ROOT));
    }

    /** What the student is expected to pay in total for this fee (the fee itself when the provider bears the charge). */
    public static long estimatedTotal(long feeKobo) {
        if (!studentPays()) return feeKobo;
        long flat = feeKobo < FLAT_WAIVED_BELOW_KOBO ? 0 : FLAT_KOBO;
        long grossed = (long) Math.ceil((feeKobo + flat) / (1 - RATE));
        return Math.min(grossed, feeKobo + CAP_KOBO);
    }

    /** The student's part of Paystack's charge on a payment: what Paystack took above the fee (0 when the provider bore it). */
    public static long studentCharge(long feeKobo, Long chargedKobo) {
        return chargedKobo == null ? 0 : Math.max(0, chargedKobo - feeKobo);
    }

    /** The provider's net: its share less whatever of Paystack's fee the student did not cover. */
    public static long providerNet(long providerShareKobo, long feeKobo, Long chargedKobo, Long gatewayFeeKobo) {
        long gatewayFee = gatewayFeeKobo == null ? 0 : gatewayFeeKobo;
        return providerShareKobo - Math.max(0, gatewayFee - studentCharge(feeKobo, chargedKobo));
    }
}
