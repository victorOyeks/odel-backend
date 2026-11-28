package com.odel.registration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odel.registration.*: how the fee is checked (http = payment-service, stub = built-in answer), the stub's answer, where
 * payment-service listens, and whether the dev-only endpoints exist.
 */
@ConfigurationProperties("odel.registration")
public record RegistrationProperties(String feeCheck, String feeStubDefault, String paymentsUrl, boolean devEndpoints) {
    public RegistrationProperties {
        if (feeCheck == null) feeCheck = "http";
        if (feeStubDefault == null) feeStubDefault = "PAID";
        if (paymentsUrl == null) paymentsUrl = "http://127.0.0.1:9306";
    }
}
