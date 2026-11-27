package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "semesters")
@Getter
@Setter
public class Semester {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID sessionId;
    private int number;
    private LocalDate startsOn;
    private LocalDate endsOn;
    private LocalDate regOpensOn;
    private LocalDate regClosesOn;
    private LocalDate lateRegClosesOn;
}
