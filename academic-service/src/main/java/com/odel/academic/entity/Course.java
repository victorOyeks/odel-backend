package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "courses")
@Getter
@Setter
public class Course {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private String code;
    private String title;
    private int units;
    private int level;
    private int semester;
    private Instant createdAt;
    private Instant updatedAt;
}
