package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "grading_rules")
@Getter
@Setter
public class GradingRule {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID programmeId;
    private int version;
    private UUID effectiveSessionId;
    private boolean inherit;
    private int caWeight;
    private int examWeight;
    private int passMark;
    private int scale;
    private boolean roundTotals;
    private String bands;
    private String standing;
    private Instant createdAt;
    private UUID createdBy;
    private String createdByName;
}
