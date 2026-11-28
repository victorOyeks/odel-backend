package com.odel.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.ApiException;
import com.odel.payment.client.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookServiceTest {
    PaymentGateway gateway = mock(PaymentGateway.class);
    PaymentEvents events = mock(PaymentEvents.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WebhookService service;
    final UUID tenant = UUID.randomUUID();
    final byte[] body = "{\"event\":\"charge.success\",\"data\":{\"id\":4099260516,\"reference\":\"WU-PF-882134\",\"status\":\"success\",\"amount\":1500000}}"
            .getBytes(StandardCharsets.UTF_8);
    final PaymentGateway.Verification ok = new PaymentGateway.Verification("success", 1_500_000, "NGN", "card", "Successful",
            Instant.now(), 32_995L, "4099260516", null, null, null, 1_532_995L);

    @BeforeEach
    void setUp() {
        service = new WebhookService(gateway, events, jdbc, new ObjectMapper(), Clock.fixed(Instant.parse("2026-12-04T13:28:00Z"), ZoneOffset.UTC));
        doReturn(List.of(tenant)).when(jdbc).query(startsWith("select tenant_id from payments"), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void aBadSignatureIsRefusedBeforeAnythingIsStored() {
        when(gateway.validSignature(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.handle(body, "nope")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("bad_signature"));
        verifyNoInteractions(jdbc, events);
    }

    @Test
    void theFirstDeliveryIsVerifiedAndApplied() {
        when(gateway.validSignature(any(), any())).thenReturn(true);
        when(jdbc.update(startsWith("insert into webhook_events"), any(Object[].class))).thenReturn(1);
        when(gateway.verify("WU-PF-882134")).thenReturn(ok);
        when(events.success("WU-PF-882134", ok, "WEBHOOK")).thenReturn(FeeLedger.Outcome.PAID);
        assertThat(service.handle(body, "sig").outcome()).isEqualTo("paid");
        verify(gateway).verify("WU-PF-882134");
        verify(events).success("WU-PF-882134", ok, "WEBHOOK");
    }

    @Test
    void theSameEventAgainChangesNothing() {
        when(gateway.validSignature(any(), any())).thenReturn(true);
        when(jdbc.update(startsWith("insert into webhook_events"), any(Object[].class))).thenReturn(0);
        assertThat(service.handle(body, "sig").outcome()).isEqualTo("duplicate");
        verifyNoInteractions(events);
        verify(gateway, never()).verify(any());
    }

    @Test
    void aFailureForgetsTheEventSoPaystacksRetryCounts() {
        when(gateway.validSignature(any(), any())).thenReturn(true);
        when(jdbc.update(startsWith("insert into webhook_events"), any(Object[].class))).thenReturn(1);
        when(gateway.verify(any())).thenThrow(new com.odel.payment.client.GatewayException("down"));
        assertThatThrownBy(() -> service.handle(body, "sig")).isInstanceOf(com.odel.payment.client.GatewayException.class);
        verify(jdbc).update(startsWith("delete from webhook_events"), any(Object[].class));
    }

    @Test
    void keysTellEventsApart() {
        var json = new ObjectMapper();
        assertThat(WebhookService.key("charge.dispute.create", json.valueToTree(java.util.Map.of("id", "9", "transaction", java.util.Map.of("reference", "R")))))
                .isNotEqualTo(WebhookService.key("charge.dispute.resolve", json.valueToTree(java.util.Map.of("id", "9", "resolution", "merchant-accepted",
                        "transaction", java.util.Map.of("reference", "R")))));
        assertThat(WebhookService.referenceOf(json.valueToTree(java.util.Map.of("transaction_reference", "WU-PF-1")))).isEqualTo("WU-PF-1");
    }
}
