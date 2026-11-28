package com.odel.registration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A late registration or course change request, decided by the coordinator of the student's centre (or an ODeL admin). */
@Entity
@Table(name = "requests")
@Getter
@Setter
public class RegistrationRequest {
    public enum Kind { LATE, CHANGE }

    public enum Status { PENDING, APPROVED, DECLINED, CANCELLED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Enumerated(EnumType.STRING)
    private Status status;
    private UUID studentId;
    private UUID userId;
    private String matricNumber;
    private String studentName;
    private String studentFirstName;
    private String studentEmail;
    private String programmeCode;
    private String programmeName;
    private Integer level;
    private UUID centreId;
    private String centreName;
    private UUID semesterId;
    private String sessionName;
    private int semesterNumber;
    private UUID registrationId;
    private String reasonCategory;
    private String reason;
    private String attachmentKey;
    private String attachmentName;
    private Long attachmentSize;
    private String attachmentType;
    private String dropCourses;
    private String addCourses;
    private Integer unitsBefore;
    private Integer unitsAfter;
    private LocalDate registerBy;
    private Instant decidedAt;
    private UUID decidedBy;
    private String decidedByName;
    private String decidedByRole;
    private String decisionNote;
    private Instant createdAt;
}
