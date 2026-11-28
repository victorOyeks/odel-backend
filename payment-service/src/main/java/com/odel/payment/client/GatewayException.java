package com.odel.payment.client;

/** The payment provider refused or could not be reached; message is safe to show (the provider's own words when it gave any). */
public class GatewayException extends RuntimeException {
    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
