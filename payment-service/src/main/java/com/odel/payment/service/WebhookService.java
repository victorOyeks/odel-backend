package com.odel.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.ApiException;
import com.odel.common.tenancy.TenantContext;
import com.odel.payment.client.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * POST /payments/webhooks/paystack. The signature is checked first (HMAC-SHA512 of the raw body); each event is applied once
 * (webhook_events); a charge.success counts only after Paystack's verify API agrees. Unknown references are ignored.
 */
@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    static final Set<String> CUSTOMER_WON = Set.of("merchant-accepted", "auto-accepted");
    private final PaymentGateway gateway;
    private final PaymentEvents events;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public WebhookService(PaymentGateway gateway, PaymentEvents events, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.gateway = gateway;
        this.events = events;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record Result(String outcome) {}

    static String referenceOf(JsonNode data) {
        for (String path : List.of("/reference", "/transaction_reference", "/transaction/reference")) {
            String v = data.at(path).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** The key that makes an event count once: event, Paystack's id, our reference and the status it reports. */
    static String key(String event, JsonNode data) {
        String id = data.path("id").asText("");
        String status = data.path("status").asText("");
        String resolution = data.path("resolution").asText("");
        String k = event + ":" + id + ":" + referenceOf(data) + ":" + status + ":" + resolution;
        return k.length() > 200 ? k.substring(0, 200) : k;
    }

    public Result handle(byte[] body, String signature) {
        if (!gateway.validSignature(body, signature)) {
            log.warn("Webhook refused: bad or missing signature");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_signature", "The webhook signature does not match.");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw ApiException.badRequest("bad_body", "The webhook body is not JSON.");
        }
        String event = root.path("event").asText("");
        JsonNode data = root.path("data");
        String reference = referenceOf(data);
        String key = key(event, data);
        UUID tenantId = reference == null ? null
                : jdbc.query("select tenant_id from payments where reference = ?", (rs, i) -> rs.getObject(1, UUID.class), reference)
                .stream().findFirst().orElse(null);
        UUID eventId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into webhook_events (id, event_key, event, reference, tenant_id, outcome, received_at) values (?, ?, ?, ?, ?, 'RECEIVED', ?)
                on conflict (event_key) do nothing""", eventId, key, event, reference, tenantId, Timestamp.from(clock.instant()));
        if (inserted == 0) return new Result("duplicate");
        String outcome;
        try {
            outcome = tenantId == null ? "ignored_unknown_reference" : TenantContext.runAs(tenantId, () -> apply(event, data, reference));
        } catch (RuntimeException e) {
            // Not applied: forget it, so Paystack's retry is applied.
            jdbc.update("delete from webhook_events where id = ?", eventId);
            throw e;
        }
        jdbc.update("update webhook_events set outcome = ? where id = ?", outcome, eventId);
        log.info("Webhook {} {} → {}", event, reference, outcome);
        return new Result(outcome);
    }

    private String apply(String event, JsonNode data, String reference) {
        return switch (event) {
            case "charge.success" -> {
                PaymentGateway.Verification v = gateway.verify(reference);
                yield events.success(reference, v, "WEBHOOK").name().toLowerCase();
            }
            case "refund.processed" -> {
                events.refundProcessed(reference, data.path("id").asText(null));
                yield "refunded";
            }
            case "refund.failed" -> {
                events.refundFailed(reference, data.path("message").asText(data.path("status").asText(null)));
                yield "refund_failed";
            }
            case "charge.dispute.create" -> {
                events.disputed(reference);
                yield "disputed";
            }
            case "charge.dispute.resolve" -> {
                if (CUSTOMER_WON.contains(data.path("resolution").asText(""))) {
                    events.reversed(reference);
                    yield "reversed";
                }
                yield "dispute_won";
            }
            default -> "ignored";
        };
    }
}
