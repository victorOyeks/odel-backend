package com.odel.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** One attempt to pay a fee, through the gateway. */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment {
    public enum Method { CARD, USSD, BANK_TRANSFER }

    public enum Status {
        PENDING, CONFIRMING, PAID, FAILED, CANCELLED, EXPIRED, REFUNDING, REFUNDED, REVERSED;

        public boolean open() { return this == PENDING || this == CONFIRMING; }
    }

    /** Payments whose money arrived (some of it may have gone back since). */
    public static final Set<Status> MONEY_RECEIVED = EnumSet.of(Status.PAID, Status.REFUNDING, Status.REFUNDED, Status.REVERSED);

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID feeId;
    private UUID studentId;
    private UUID userId;
    private String studentName;
    private String matricNumber;
    private String feeLabel;
    private String reference;
    @Enumerated(EnumType.STRING)
    private Method method;
    @Enumerated(EnumType.STRING)
    private Status status;
    private long amountKobo;
    private long providerShareKobo;
    private long centreShareKobo;
    private Long gatewayFeeKobo;
    private String subaccountCode;
    private String email;
    private String authorizationUrl;
    private String accessCode;
    private String ussdBank;
    private String ussdCode;
    private String transferBank;
    private String transferAccountNumber;
    private String transferAccountName;
    private Instant expiresAt;
    private String channel;
    private String cardType;
    private String cardBank;
    private String cardLast4;
    private String gatewayTransactionId;
    private String gatewayMessage;
    private String failureReason;
    private boolean duplicate;
    private Instant claimedAt;
    private Instant paidAt;
    private String confirmedBy;
    private Instant disputedAt;
    private Instant reversedAt;
    private Instant lastCheckedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
