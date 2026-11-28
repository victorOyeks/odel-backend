package com.odel.registration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A student's course registration for one semester. Final once submitted; changed only by an approved course change. */
@Entity
@Table(name = "registrations")
@Getter
@Setter
public class Registration {
    public enum Status { REGISTERED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID studentId;
    private UUID userId;
    private String matricNumber;
    private String studentName;
    private UUID semesterId;
    private UUID sessionId;
    private String sessionName;
    private int semesterNumber;
    private UUID programmeId;
    private String programmeCode;
    private String programmeName;
    private int level;
    private UUID centreId;
    private String centreCode;
    private String centreName;
    @Enumerated(EnumType.STRING)
    private Status status;
    private boolean late;
    private int totalUnits;
    private int courseCount;
    private String slipNumber;
    private int slipVersion;
    private String verificationCode;
    private Instant submittedAt;
    private Instant updatedAt;
    private Instant changedAt;
}
