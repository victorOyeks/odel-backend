package com.odel.registration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One course on a registration, as it was when added (code, title and units kept for the slip). */
@Entity
@Table(name = "registration_courses")
@Getter
@Setter
public class RegistrationCourse {
    public enum Kind { COMPULSORY, ELECTIVE, CARRY_OVER }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID registrationId;
    private UUID courseId;
    private String code;
    private String title;
    private int units;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private UUID carryOverId;
}
