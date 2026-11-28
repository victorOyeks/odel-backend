package com.odel.payment.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The payment provider. Two implementations: {@link PaystackGateway} (api.paystack.co, ODEL_PAYSTACK_MODE=paystack) and
 * {@link FakeGateway} (in memory, posts signed webhooks to this service; ODEL_PAYSTACK_MODE=fake, the local default).
 * Amounts are in kobo. A refusal from the provider is a {@link GatewayException}.
 */
public interface PaymentGateway {
    String mode();

    /** What every transaction carries: who pays, how much, our reference and the split. */
    record Charge(String email, long amountKobo, String reference, String subaccountCode, long providerShareKobo, Map<String, Object> metadata) {}

    record Checkout(String authorizationUrl, String accessCode) {}

    /** Hosted checkout (card, and Paystack's own transfer/USSD tabs); the browser comes back to callbackUrl?reference=. */
    Checkout initialize(Charge charge, String callbackUrl);

    record UssdCode(String code, String displayText) {}

    /** Charge API, USSD: ussdType is the bank's short code (737 GTBank, 919 UBA, 822 Sterling, 966 Zenith). */
    UssdCode chargeUssd(Charge charge, String ussdType);

    /** amountKobo: what Paystack expects into this account (the fee plus its charge when the student pays the charge). */
    record TransferAccount(String bankName, String accountNumber, String accountName, Instant expiresAt, long amountKobo) {}

    /** Charge API, pay with transfer: a one-time account for this exact amount. */
    TransferAccount chargeTransfer(Charge charge, Instant expiresAt);

    /**
     * GET /transaction/verify. amountKobo is what we asked for (the fee); chargedKobo what the payer was charged. status: success | failed | abandoned | ongoing | pending | processing | queued | reversed | not_found.
     */
    record Verification(String status, long amountKobo, String currency, String channel, String gatewayResponse, Instant paidAt,
                        Long feesKobo, String transactionId, String cardType, String cardBank, String cardLast4, Long chargedKobo) {
        public boolean success() { return "success".equals(status); }
        public boolean failed() { return "failed".equals(status) || "reversed".equals(status); }
    }

    Verification verify(String reference);

    record RefundStarted(String refundId, String status, Instant expectedAt) {}

    RefundStarted refund(String reference, long amountKobo, String note);

    record Bank(String code, String name) {}

    List<Bank> banks();

    /** The account's name as the bank holds it; null when the bank cannot find the account. */
    String resolveAccount(String bankCode, String accountNumber);

    /** POST /subaccount, or PUT /subaccount/{code} when existingCode is given. Returns the subaccount code. */
    String saveSubaccount(String existingCode, String businessName, String bankCode, String accountNumber, double percentageCharge);

    /** Whether a webhook's x-paystack-signature matches its raw body. */
    boolean validSignature(byte[] body, String signature);
}
