package com.odel.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** odel.payment.*: which gateway, its keys (from the environment only), the default fee and split. Amounts in naira. */
@ConfigurationProperties("odel.payment")
public record PaymentProperties(String mode, String secretKey, String publicKey, String baseUrl, long defaultFee, long defaultCentreShare,
                                String fallbackEmailDomain, boolean recheckEnabled, String officePhone, Seed seed) {
    public record Seed(String slug, String bankCode, String accountNumber) {}

    public boolean fake() {
        return !"paystack".equalsIgnoreCase(mode);
    }

    /** The key webhooks are signed with: the real secret key, or a fixed dev value for the fake gateway without one. */
    public String signingKey() {
        return secretKey == null || secretKey.isBlank() ? "fake-paystack-secret" : secretKey;
    }
}
