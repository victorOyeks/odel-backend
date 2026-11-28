package com.odel.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Money going back through the gateway: a second payment for a fee already paid (E-07), or a refund made in Paystack. */
@Entity
@Table(name = "refunds")
@Getter
@Setter
public class Refund {
    /** NOT_SENT: Paystack could not be reached yet; the re-check job tries again. */
    public enum Status { NOT_SENT, PENDING, PROCESSED, FAILED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID paymentId;
    private String reference;
    private String gatewayRefundId;
    private long amountKobo;
    private String reason;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String lastError;
    private Instant startedAt;
    private Instant processedAt;
    private LocalDate expectedBy;
}
