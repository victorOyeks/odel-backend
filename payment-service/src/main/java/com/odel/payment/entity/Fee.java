package com.odel.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/** The platform fee of one student for one semester ("2026/2027 · 1st semester"). */
@Entity
@Table(name = "fees")
@Getter
@Setter
public class Fee {
    /** Stored status. CONFIRMING is not stored: it is NOT_PAID, REFUNDED or REVERSED with a payment waiting for the bank. */
    public enum Status { NOT_PAID, PAID, REFUNDED, REVERSED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID studentId;
    private UUID userId;
    private UUID semesterId;
    private UUID sessionId;
    private String sessionName;
    private int semesterNumber;
    private long amountKobo;
    @Enumerated(EnumType.STRING)
    private Status status;
    private UUID paidPaymentId;
    private Instant paidAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static String semesterName(int number) {
        return number == 1 ? "1st semester" : number == 2 ? "2nd semester" : number + "th semester";
    }

    public String label() {
        return sessionName + " · " + semesterName(semesterNumber);
    }
}
