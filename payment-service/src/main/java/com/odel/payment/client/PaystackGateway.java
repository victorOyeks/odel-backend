package com.odel.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.payment.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Paystack over HTTP (api.paystack.co) with the secret key from the environment.
 * Split on every transaction: subaccount = the university's code, transaction_charge = the provider's flat share (₦12,000),
 * bearer = account, so Paystack's charge comes out of the provider's share and the centre always receives its ₦3,000.
 */
public class PaystackGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(PaystackGateway.class);
    private final RestClient http;
    private final ObjectMapper json;
    private final String secretKey;
    private final AtomicReference<Cached> banks = new AtomicReference<>();

    record Cached(List<Bank> banks, Instant at) {}

    public PaystackGateway(PaymentProperties props, ObjectMapper json) {
        if (props.secretKey() == null || props.secretKey().isBlank()) {
            throw new IllegalStateException("ODEL_PAYSTACK_MODE=paystack needs ODEL_PAYSTACK_SECRET_KEY");
        }
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
        this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(f)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.secretKey()).build();
        this.json = json;
        this.secretKey = props.secretKey();
    }

    @Override
    public String mode() {
        return "paystack";
    }

    private Map<String, Object> base(Charge c) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("email", c.email());
        b.put("amount", c.amountKobo());
        b.put("reference", c.reference());
        b.put("currency", "NGN");
        if (c.subaccountCode() != null) {
            b.put("subaccount", c.subaccountCode());
            b.put("transaction_charge", c.providerShareKobo());
            b.put("bearer", "account");
        }
        if (c.metadata() != null) b.put("metadata", c.metadata());
        return b;
    }

    @Override
    public Checkout initialize(Charge charge, String callbackUrl) {
        Map<String, Object> b = base(charge);
        b.put("callback_url", callbackUrl);
        // Closing the checkout (× → Cancel Payment) goes back to our return page for this payment, which then shows the decline
        // (P-07) or "You closed the payment page" (P-08); without it Paystack only sends the browser back a page.
        Map<String, Object> meta = new LinkedHashMap<>(charge.metadata() == null ? Map.of() : charge.metadata());
        meta.put("cancel_action", callbackUrl + (callbackUrl.contains("?") ? "&" : "?") + "reference="
                + URLEncoder.encode(charge.reference(), StandardCharsets.UTF_8) + "&closed=1");
        b.put("metadata", meta);
        JsonNode d = post("/transaction/initialize", b);
        return new Checkout(d.path("authorization_url").asText(), d.path("access_code").asText(null));
    }

    @Override
    public UssdCode chargeUssd(Charge charge, String ussdType) {
        Map<String, Object> b = base(charge);
        b.put("ussd", Map.of("type", ussdType));
        JsonNode d = post("/charge", b);
        String code = d.path("ussd_code").asText(null);
        if (code == null || code.isBlank()) throw new GatewayException(text(d.path("display_text"), "Paystack did not give a USSD code. Try again or pay by card."));
        return new UssdCode(code, d.path("display_text").asText(null));
    }

    @Override
    public TransferAccount chargeTransfer(Charge charge, Instant expiresAt) {
        Map<String, Object> b = base(charge);
        b.put("bank_transfer", Map.of("account_expires_at", expiresAt.toString()));
        JsonNode d = post("/charge", b);
        String number = d.path("account_number").asText(null);
        if (number == null) throw new GatewayException(text(d.path("display_text"), "Paystack did not give an account to pay into. Try again or pay by card."));
        Instant exp = parseTime(d.path("account_expires_at").asText(null));
        // With the charge passed to the customer, Paystack expects the fee plus its charge (₦15,329.95 for ₦15,000).
        long expected = d.path("amount").asLong(0) > 0 ? d.path("amount").asLong() : charge.amountKobo();
        return new TransferAccount(d.path("bank").path("name").asText("Paystack-Titan"), number, d.path("account_name").asText(null),
                exp == null ? expiresAt : exp, expected);
    }

    @Override
    public Verification verify(String reference) {
        JsonNode d;
        try {
            d = get("/transaction/verify/" + reference);
        } catch (GatewayException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return new Verification("not_found", 0, "NGN", null, e.getMessage(), null, null, null, null, null, null, null);
            }
            throw e;
        }
        JsonNode auth = d.path("authorization");
        // When the account passes Paystack's charge on to the customer, `amount` includes that charge (₦15,000 becomes ₦15,329.95);
        // `requested_amount` is what we asked for, so that is what the fee is checked against.
        long requested = d.path("requested_amount").asLong(0);
        long amount = requested > 0 ? requested : d.path("amount").asLong();
        return new Verification(d.path("status").asText("pending"), amount, d.path("currency").asText("NGN"),
                d.path("channel").asText(null), d.path("gateway_response").asText(null), parseTime(d.path("paid_at").asText(null)),
                d.hasNonNull("fees") ? d.path("fees").asLong() : null, d.path("id").asText(null),
                auth.path("card_type").asText(null), auth.path("bank").asText(null), auth.path("last4").asText(null),
                d.hasNonNull("amount") ? d.path("amount").asLong() : null);
    }

    @Override
    public RefundStarted refund(String reference, long amountKobo, String note) {
        JsonNode d = post("/refund", Map.of("transaction", reference, "amount", amountKobo, "merchant_note", note, "customer_note", note));
        return new RefundStarted(d.path("id").asText(null), d.path("status").asText("pending"), parseTime(d.path("expected_at").asText(null)));
    }

    @Override
    public List<Bank> banks() {
        Cached c = banks.get();
        if (c != null && c.at().isAfter(Instant.now().minus(Duration.ofDays(1)))) return c.banks();
        JsonNode d = get("/bank?country=nigeria&perPage=200");
        List<Bank> out = new ArrayList<>();
        for (JsonNode b : d) {
            if (b.path("active").asBoolean(true) && !b.path("is_deleted").asBoolean(false)) out.add(new Bank(b.path("code").asText(), b.path("name").asText()));
        }
        banks.set(new Cached(List.copyOf(out), Instant.now()));
        return out;
    }

    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        try {
            JsonNode d = get("/bank/resolve?account_number=" + accountNumber + "&bank_code=" + bankCode);
            String name = d.path("account_name").asText(null);
            return name == null || name.isBlank() ? null : name;
        } catch (GatewayException e) {
            log.info("Paystack could not resolve an account at bank {}: {}", bankCode, e.getMessage());
            return null;
        }
    }

    @Override
    public String saveSubaccount(String existingCode, String businessName, String bankCode, String accountNumber, double percentageCharge) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("business_name", businessName);
        b.put("settlement_bank", bankCode);
        b.put("account_number", accountNumber);
        b.put("percentage_charge", percentageCharge);
        b.put("description", "ODeL centre share: " + businessName);
        JsonNode d = existingCode == null ? post("/subaccount", b) : put("/subaccount/" + existingCode, b);
        String code = d.path("subaccount_code").asText(null);
        return code == null || code.isBlank() ? existingCode : code;
    }

    @Override
    public boolean validSignature(byte[] body, String signature) {
        return WebhookSignature.valid(body, signature, secretKey);
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode get(String path) {
        return call(() -> http.get().uri(path).retrieve().body(String.class));
    }

    private JsonNode post(String path, Object body) {
        return call(() -> http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
    }

    private JsonNode put(String path, Object body) {
        return call(() -> http.put().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
    }

    /** Paystack answers {"status":true|false,"message","data"}; data is returned, a refusal becomes a GatewayException with its message. */
    private JsonNode call(java.util.function.Supplier<String> work) {
        String raw;
        try {
            raw = work.get();
        } catch (RestClientResponseException e) {
            JsonNode n = null;
            try {
                n = json.readTree(e.getResponseBodyAsString());
            } catch (Exception ignored) {
                // not JSON
            }
            if (n != null && n.isObject()) throw refusal(n, "Paystack refused the request (" + e.getStatusCode().value() + ").");
            throw new GatewayException(message(e.getResponseBodyAsString(), "Paystack refused the request (" + e.getStatusCode().value() + ")."));
        } catch (Exception e) {
            throw new GatewayException("We could not reach Paystack. Try again in a minute.", e);
        }
        try {
            JsonNode n = json.readTree(raw);
            if (!n.path("status").asBoolean(false)) throw refusal(n, "Paystack refused the request.");
            return n.path("data");
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("Paystack sent an answer we could not read.", e);
        }
    }

    /**
     * A refusal names Paystack's own reason: the top message ("Charge attempted") is often generic and the reason is in data
     * (message, gateway_response or display_text). Logged with the data's status so a refused charge can be followed up.
     */
    private GatewayException refusal(JsonNode n, String fallback) {
        JsonNode d = n.path("data");
        String top = text(n.path("message"), fallback);
        String why = text(d.path("message"), text(d.path("gateway_response"), text(d.path("display_text"), null)));
        log.warn("Paystack refused: message={} data.status={} reason={}", top, d.path("status").asText(null), why);
        return new GatewayException(why == null || why.equalsIgnoreCase(top) ? top : top + ": " + why);
    }

    private String message(String body, String fallback) {
        try {
            return text(json.readTree(body).path("message"), fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String text(JsonNode n, String fallback) {
        String s = n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
        return s == null || s.isBlank() ? fallback : s;
    }

    static Instant parseTime(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(s);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
