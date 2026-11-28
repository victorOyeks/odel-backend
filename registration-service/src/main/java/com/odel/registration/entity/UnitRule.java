package com.odel.registration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Unit limits for one level, for the university (programmeId null) or one programme (A-05b). */
@Entity
@Table(name = "unit_rules")
@Getter
@Setter
public class UnitRule {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID programmeId;
    private int level;
    private int minUnits;
    private int maxUnits;
    private int maxElectives;
    private Instant updatedAt;
    private String updatedByName;
}
