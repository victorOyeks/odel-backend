package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "departments")
@Getter
@Setter
public class Department {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID facultyId;
    private String name;
    private String code;
    private Instant createdAt;
    private Instant updatedAt;
}
