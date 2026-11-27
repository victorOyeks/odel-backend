package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "staff_assignments")
@Getter
@Setter
public class StaffAssignment {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    public enum Kind { LECTURER, FACILITATOR, EXAM_OFFICER, COORDINATOR }
    private UUID sessionId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private UUID courseId;
    private UUID centreId;
    private UUID userId;
    private Instant createdAt;
    private UUID createdBy;
}
