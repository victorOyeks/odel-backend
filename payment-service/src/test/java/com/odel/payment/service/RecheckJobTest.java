package com.odel.payment.service;

import com.odel.payment.client.PaymentGateway;
import com.odel.payment.config.PaymentProperties;
import com.odel.payment.entity.Payment;
import com.odel.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecheckJobTest {
    final Instant now = Instant.parse("2026-12-04T14:00:00Z");
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentGateway gateway = mock(PaymentGateway.class);
    PaymentEvents events = mock(PaymentEvents.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    RecheckJob job;
    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PaymentProperties props = new PaymentProperties("fake", null, null, null, 15000, 3000, "x.invalid", true, null, null);
        job = new RecheckJob(jdbc, payments, gateway, events, props, tx, Clock.fixed(now, ZoneOffset.UTC));
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        doReturn(List.of()).when(jdbc).query(startsWith("select id, tenant_id from refunds"), any(RowMapper.class));
    }

    private void due(Payment p) {
        doReturn(List.of(new RecheckJob.Due(p.getId(), tenant, p.getReference()))).when(jdbc)
                .query(contains("status in ('PENDING', 'CONFIRMING')"), any(RowMapper.class), any(Object[].class));
        when(payments.findInTenant(p.getId())).thenReturn(Optional.of(p));
    }

    @Test
    void onlyPaymentsOpenThirtyMinutesAreAskedAndEachAtMostEveryTenMinutes() {
        doReturn(List.of()).when(jdbc).query(contains("status in ('PENDING', 'CONFIRMING')"), any(RowMapper.class), any(Object[].class));
        job.run(now);
        verify(jdbc).query(contains("created_at <= ?"), any(RowMapper.class),
                eq(Timestamp.from(now.minus(Duration.ofMinutes(30)))), eq(Timestamp.from(now.minus(Duration.ofMinutes(10)))));
    }

    @Test
    void aLostWebhookIsCaughtByTheRecheck() {
        Payment p = FeeLedgerTest.payment(FeeLedgerTest.fee(), Payment.Method.CARD);
        due(p);
        var ok = FeeLedgerTest.ok(1_500_000);
        when(gateway.verify(p.getReference())).thenReturn(ok);
        when(events.success(p.getReference(), ok, "RECHECK")).thenReturn(FeeLedger.Outcome.PAID);
        assertThat(job.run(now).paid()).isEqualTo(1);
        verify(events).success(p.getReference(), ok, "RECHECK");
    }

    @Test
    void aTransferWithNothingAfterItsTimeExpires() {
        Payment p = FeeLedgerTest.payment(FeeLedgerTest.fee(), Payment.Method.BANK_TRANSFER);
        p.setCreatedAt(now.minus(Duration.ofMinutes(45)));
        p.setExpiresAt(now.minus(Duration.ofMinutes(15)));
        due(p);
        when(gateway.verify(p.getReference())).thenReturn(FeeLedgerTest.status("abandoned", null));
        assertThat(job.run(now).expired()).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(Payment.Status.EXPIRED);
        verify(events, never()).success(any(), any(), any());
    }

    @Test
    void aTransferTheBankIsStillProcessingWaits() {
        Payment p = FeeLedgerTest.payment(FeeLedgerTest.fee(), Payment.Method.BANK_TRANSFER);
        p.setStatus(Payment.Status.CONFIRMING);
        p.setCreatedAt(now.minus(Duration.ofMinutes(45)));
        p.setExpiresAt(now.minus(Duration.ofMinutes(15)));
        due(p);
        when(gateway.verify(p.getReference())).thenReturn(FeeLedgerTest.status("pending", null));
        job.run(now);
        assertThat(p.getStatus()).isEqualTo(Payment.Status.CONFIRMING);
        assertThat(p.getLastCheckedAt()).isEqualTo(now);
    }

    @Test
    void aDeclineFoundLaterIsFailed() {
        Payment p = FeeLedgerTest.payment(FeeLedgerTest.fee(), Payment.Method.CARD);
        due(p);
        when(gateway.verify(p.getReference())).thenReturn(FeeLedgerTest.status("failed", "Declined"));
        assertThat(job.run(now).failed()).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(Payment.Status.FAILED);
    }
}
