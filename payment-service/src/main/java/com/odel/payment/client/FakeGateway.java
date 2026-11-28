package com.odel.payment.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.payment.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for Paystack locally and in tests (ODEL_PAYSTACK_MODE=fake): transactions live in memory, the checkout is a plain
 * dev page, and every outcome can post a webhook to this service signed exactly as Paystack signs (HMAC-SHA512 of the body).
 * Card 4412 at GTBank succeeds; "decline" is the bank refusing; transfers can stay pending and be confirmed later.
 */
public class FakeGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(FakeGateway.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PaymentProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final String selfUrl;
    private final RestClient http = RestClient.create();
    private final Map<String, Txn> txns = new ConcurrentHashMap<>();

    /** One fake transaction. status as Paystack's verify reports it. */
    static final class Txn {
        String reference, email, callbackUrl, status = "abandoned", channel, gatewayResponse, refundId, disputeId;
        long amountKobo, id;
        Instant paidAt;
    }

    public record Webhook(String event, String body, String signature) {}

    public FakeGateway(PaymentProperties props, ObjectMapper json, Clock clock, String selfUrl) {
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.selfUrl = selfUrl;
    }

    @Override
    public String mode() {
        return "fake";
    }

    private Txn open(Charge c, String channel) {
        Txn t = new Txn();
        t.reference = c.reference();
        t.email = c.email();
        t.amountKobo = c.amountKobo();
        t.channel = channel;
        t.id = 4_000_000_000L + RANDOM.nextInt(900_000_000);
        txns.put(c.reference(), t);
        return t;
    }

    @Override
    public Checkout initialize(Charge charge, String callbackUrl) {
        Txn t = open(charge, "card");
        t.callbackUrl = callbackUrl;
        // Relative, like dev file links: the browser opens it through the web app's /api proxy.
        return new Checkout("/api/v1/dev/payments/checkout/" + charge.reference(), "fake_" + t.id);
    }

    @Override
    public UssdCode chargeUssd(Charge charge, String ussdType) {
        Txn t = open(charge, "ussd");
        t.status = "pending";
        String digits = charge.reference().replaceAll("\\D", "");
        String code = "*" + ussdType + "*000*" + (charge.amountKobo() / 100) + "*" + digits.substring(Math.max(0, digits.length() - 5)) + "#";
        return new UssdCode(code, "Dial " + code + " on the phone number linked to your bank account to complete the payment");
    }

    @Override
    public TransferAccount chargeTransfer(Charge charge, Instant expiresAt) {
        Txn t = open(charge, "bank_transfer");
        t.status = "pending";
        return new TransferAccount("Paystack-Titan", "99" + String.format("%08d", RANDOM.nextInt(100_000_000)), "PAYSTACK CHECKOUT", expiresAt);
    }

    @Override
    public Verification verify(String reference) {
        Txn t = txns.get(reference);
        if (t == null) return new Verification("not_found", 0, "NGN", null, "Transaction reference not found", null, null, null, null, null, null);
        boolean card = "card".equals(t.channel);
        boolean paid = t.paidAt != null;
        return new Verification(t.status, t.amountKobo, "NGN", t.channel, t.gatewayResponse, t.paidAt,
                paid ? fees(t.amountKobo) : null, String.valueOf(t.id), card && paid ? "visa DEBIT" : null, card && paid ? "GTBank" : null,
                card && paid ? "4412" : null);
    }

    /** Paystack's local fee: 1.5% + ₦100, capped at ₦2,000. */
    public static long fees(long amountKobo) {
        return Math.min(amountKobo * 15 / 1000 + 10_000, 200_000);
    }

    @Override
    public RefundStarted refund(String reference, long amountKobo, String note) {
        Txn t = txns.get(reference);
        if (t == null || t.paidAt == null) throw new GatewayException("Transaction has not been paid.");
        t.refundId = String.valueOf(10_000_000 + RANDOM.nextInt(80_000_000));
        return new RefundStarted(t.refundId, "pending", clock.instant().plusSeconds(10L * 24 * 3600));
    }

    @Override
    public List<Bank> banks() {
        return List.of(new Bank("001", "Paystack Test Bank"), new Bank("044", "Access Bank"), new Bank("058", "Guaranty Trust Bank"),
                new Bank("011", "First Bank of Nigeria"), new Bank("232", "Sterling Bank"), new Bank("033", "United Bank For Africa"),
                new Bank("035", "Wema Bank"), new Bank("057", "Zenith Bank"));
    }

    /** Any 10 digits resolve, except 1111111111 (the "bank cannot find it" case). */
    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        if (banks().stream().noneMatch(b -> b.code().equals(bankCode))) return null;
        if ("1111111111".equals(accountNumber)) return null;
        return "ODEL CENTRE TEST ACCOUNT " + accountNumber.substring(6);
    }

    @Override
    public String saveSubaccount(String existingCode, String businessName, String bankCode, String accountNumber, double percentageCharge) {
        return existingCode != null ? existingCode : "ACCT_FAKE" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase().substring(0, 8);
    }

    @Override
    public boolean validSignature(byte[] body, String signature) {
        return WebhookSignature.valid(body, signature, props.signingKey());
    }

    // ------------------------------------------------------------------ the dev side: checkout page and outcomes

    public String callbackUrl(String reference) {
        Txn t = txns.get(reference);
        return t == null ? null : t.callbackUrl;
    }

    public boolean known(String reference) {
        return txns.containsKey(reference);
    }

    public long amountKobo(String reference) {
        Txn t = txns.get(reference);
        return t == null ? 0 : t.amountKobo;
    }

    /**
     * SUCCESS (money arrived), DECLINE (the bank refused), PENDING (a transfer the bank is still processing), CLOSE (checkout closed),
     * REVERSE (dispute opened and lost: the bank took the money back), REFUND_PROCESSED (the refund reached the customer).
     * Returns the webhooks this produces; they are posted to this service when post is true.
     */
    public List<Webhook> simulate(String reference, String outcome, String channel, boolean post) {
        Txn t = txns.get(reference);
        if (t == null) throw new GatewayException("The fake gateway does not know " + reference + " (it forgets on restart).");
        if (channel != null && !channel.isBlank()) t.channel = channel;
        List<Webhook> hooks = new ArrayList<>();
        switch (outcome.toUpperCase()) {
            case "SUCCESS" -> {
                t.status = "success";
                t.gatewayResponse = "Successful";
                if (t.paidAt == null) t.paidAt = clock.instant();
                Map<String, Object> data = txnData(t);
                hooks.add(webhook("charge.success", data));
            }
            case "DECLINE" -> {
                t.status = "failed";
                t.gatewayResponse = "Declined";
            }
            case "INSUFFICIENT_FUNDS" -> {
                t.status = "failed";
                t.gatewayResponse = "Insufficient Funds";
            }
            case "PENDING" -> t.status = "pending";
            case "CLOSE" -> t.status = "abandoned";
            case "REVERSE" -> {
                t.disputeId = String.valueOf(1_000_000 + RANDOM.nextInt(8_000_000));
                Map<String, Object> tx = Map.of("reference", t.reference, "amount", t.amountKobo, "id", t.id);
                hooks.add(webhook("charge.dispute.create", Map.of("id", t.disputeId, "status", "awaiting-merchant-feedback", "transaction", tx)));
                hooks.add(webhook("charge.dispute.resolve", Map.of("id", t.disputeId, "status", "resolved", "resolution", "merchant-accepted",
                        "resolvedAt", clock.instant().toString(), "transaction", tx)));
                t.status = "reversed";
            }
            case "REFUND_PROCESSED" -> {
                if (t.refundId == null) throw new GatewayException("No refund was started for " + reference + ".");
                hooks.add(webhook("refund.processed", Map.of("id", t.refundId, "status", "processed", "transaction_reference", t.reference,
                        "amount", t.amountKobo, "currency", "NGN")));
            }
            default -> throw new GatewayException("Unknown outcome " + outcome + ".");
        }
        if (post) hooks.forEach(this::post);
        return hooks;
    }

    private Map<String, Object> txnData(Txn t) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", t.id);
        d.put("domain", "test");
        d.put("status", t.status);
        d.put("reference", t.reference);
        d.put("amount", t.amountKobo);
        d.put("currency", "NGN");
        d.put("channel", t.channel);
        d.put("gateway_response", t.gatewayResponse);
        d.put("paid_at", t.paidAt == null ? null : t.paidAt.toString());
        d.put("fees", fees(t.amountKobo));
        if ("card".equals(t.channel)) d.put("authorization", Map.of("last4", "4412", "bank", "GTBank", "card_type", "visa DEBIT", "channel", "card"));
        d.put("customer", Map.of("email", t.email));
        return d;
    }

    private Webhook webhook(String event, Map<String, Object> data) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event", event);
            body.put("data", data);
            String raw = json.writeValueAsString(body);
            return new Webhook(event, raw, WebhookSignature.sign(raw.getBytes(StandardCharsets.UTF_8), props.signingKey()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Posts to this service's own webhook endpoint, as Paystack would. */
    public int post(Webhook w) {
        try {
            return http.post().uri(selfUrl + "/api/v1/payments/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                    .header("x-paystack-signature", w.signature()).body(w.body()).retrieve().toBodilessEntity().getStatusCode().value();
        } catch (Exception e) {
            log.warn("Fake gateway could not post {}: {}", w.event(), e.getMessage());
            return 0;
        }
    }
}
