package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Getter
@Setter
public class AcademicSession {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    public enum Status { DRAFT, ACTIVE, ARCHIVED }
    private String name;
    @Enumerated(EnumType.STRING)
    private Status status;
    private LocalDate startsOn;
    private LocalDate endsOn;
    private Instant createdAt;
    private Instant updatedAt;
    private UUID createdBy;
}
