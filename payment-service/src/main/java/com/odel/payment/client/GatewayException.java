package com.odel.payment.client;

/** The payment provider refused or could not be reached; message is safe to show (the provider's own words when it gave any). */
public class GatewayException extends RuntimeException {
    /** True when Paystack answered and said no (a refusal with its reason); false when it could not be reached or read. */
    private final boolean refused;

    public GatewayException(String message) {
        this(message, false);
    }

    public GatewayException(String message, boolean refused) {
        super(message);
        this.refused = refused;
    }

    public boolean refused() {
        return refused;
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
        this.refused = false;
    }
}
