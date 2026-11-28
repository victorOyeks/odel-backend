package com.odel.registration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A failed course the student must take again; pinned on registration in the semester the course runs. */
@Entity
@Table(name = "carry_overs")
@Getter
@Setter
public class CarryOver {
    public enum Source { ADMIN, IMPORT, RESULTS }

    public enum Status { OPEN, CLEARED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID studentId;
    private UUID courseId;
    private String courseCode;
    private String courseTitle;
    private int units;
    private int semester;
    private String fromSession;
    @Enumerated(EnumType.STRING)
    private Source source;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant createdAt;
    private String createdByName;
}
