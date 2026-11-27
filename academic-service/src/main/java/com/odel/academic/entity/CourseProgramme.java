package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "course_programmes")
@Getter
@Setter
public class CourseProgramme {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    public enum Kind { COMPULSORY, ELECTIVE }
    private UUID courseId;
    private UUID programmeId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
}
