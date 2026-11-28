package com.odel.payment.service;

import com.odel.payment.dto.PaymentDtos.UssdBank;

import java.util.List;
import java.util.Locale;

/** The banks Paystack's Charge API supports for USSD: GTBank, UBA, Sterling and Zenith. Others pay by card or transfer. */
public final class UssdBanks {
    private UssdBanks() {}

    public static final List<UssdBank> ALL = List.of(
            new UssdBank("GTBANK", "GTBank", "*737#"),
            new UssdBank("UBA", "UBA", "*919#"),
            new UssdBank("STERLING", "Sterling", "*822#"),
            new UssdBank("ZENITH", "Zenith", "*966#"));

    public static final String OTHERS = "USSD works with GTBank, UBA, Sterling and Zenith. Another bank? Pay by card or transfer.";

    public static UssdBank find(String key) {
        if (key == null) return null;
        String k = key.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return ALL.stream().filter(b -> b.key().equals(k) || b.name().toUpperCase(Locale.ROOT).equals(k)
                || (k.equals("GTB") && b.key().equals("GTBANK"))).findFirst().orElse(null);
    }

    /** Paystack's ussd.type: the short code without * and #. */
    public static String type(UssdBank b) {
        return b.code().replaceAll("[*#]", "");
    }

    public static String name(String key) {
        UssdBank b = find(key);
        return b == null ? key : b.name();
    }
}
