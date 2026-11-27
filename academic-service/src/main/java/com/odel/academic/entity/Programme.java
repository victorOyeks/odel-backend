package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "programmes")
@Getter
@Setter
public class Programme {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID departmentId;
    private String name;
    private String code;
    private String degree;
    private int durationYears;
    /** Comma-separated, e.g. "100,200". */
    private String entryLevels;
    private Integer minUnitsToGraduate;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    
    public java.util.List<Integer> levels() {
        return java.util.stream.IntStream.rangeClosed(1, durationYears).map(i -> i * 100).boxed().toList();
    }
    
    public java.util.List<Integer> entryLevelList() {
        return java.util.Arrays.stream(entryLevels.split(",")).filter(s -> !s.isBlank()).map(String::trim).map(Integer::valueOf).toList();
    }
}
