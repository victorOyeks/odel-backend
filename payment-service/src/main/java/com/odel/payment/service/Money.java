package com.odel.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Kobo in, naira out: "₦15,000" for cards and lists, "₦15,000.00" where the exact amount matters (transfers, receipts). */
public final class Money {
    private Money() {}

    public static BigDecimal naira(long kobo) {
        return BigDecimal.valueOf(kobo).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY).stripTrailingZeros()
                .setScale(kobo % 100 == 0 ? 0 : 2, RoundingMode.UNNECESSARY);
    }

    public static long kobo(BigDecimal naira) {
        return naira.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static String label(long kobo) {
        return "₦" + (kobo % 100 == 0 ? format(kobo, "#,##0") : format(kobo, "#,##0.00"));
    }

    public static String exact(long kobo) {
        return "₦" + format(kobo, "#,##0.00");
    }

    /** For the PDF: its standard fonts have no naira sign. */
    public static String ngn(long kobo) {
        return "NGN " + format(kobo, "#,##0.00");
    }

    private static String format(long kobo, String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US)).format(BigDecimal.valueOf(kobo).movePointLeft(2));
    }

    /** The split: the provider's flat share is what is left after the centre's share. */
    public static long providerShare(long amountKobo, long centreShareKobo) {
        if (centreShareKobo < 0 || centreShareKobo > amountKobo) throw new IllegalArgumentException("centre share out of range");
        return amountKobo - centreShareKobo;
    }
}
