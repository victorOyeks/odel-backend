package com.odel.student.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A student moved to another study centre (A-06): from, to, why, from when, and who was told. */
@Entity
@Table(name = "centre_moves")
@Getter
@Setter
public class CentreMove {
    public enum Status { SCHEDULED, DONE, CANCELLED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID studentId;
    private UUID fromCentreId;
    private String fromCentreCode;
    private String fromCentreName;
    private UUID toCentreId;
    private String toCentreCode;
    private String toCentreName;
    private String reason;
    private LocalDate effectiveOn;
    @Enumerated(EnumType.STRING)
    private Status status;
    private UUID requestedBy;
    private String requestedByName;
    private String notified;
    private String notNotified;
    private Integer requestsMoved;
    private Instant createdAt;
    private Instant appliedAt;
    private Instant cancelledAt;
}
