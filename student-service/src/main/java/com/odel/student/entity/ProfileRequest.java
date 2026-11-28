package com.odel.student.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/** Something on the record only the ODeL office may change: a name, date of birth or gender correction with proof (L-14i), or a new photo (L-14h). */
@Entity
@Table(name = "profile_requests")
@Getter
@Setter
public class ProfileRequest {
    public enum Kind { CORRECTION, PHOTO }

    public enum Status {
        PENDING("Waiting for the ODeL office"), APPROVED("Approved"), DECLINED("Not approved"), WITHDRAWN("Withdrawn");

        private final String label;

        Status(String label) { this.label = label; }

        public String label() { return label; }
    }

    public enum Field {
        SURNAME("Surname spelling"), FIRST_NAME("First name spelling"), OTHER_NAMES("Other names"), DATE_OF_BIRTH("Date of birth"), GENDER("Gender");

        private final String label;

        Field(String label) { this.label = label; }

        public String label() { return label; }
    }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID studentId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Enumerated(EnumType.STRING)
    private Field field;
    private String currentValue;
    private String requestedValue;
    private String note;
    private String fileKey;
    private String fileName;
    private Long fileSize;
    private String fileType;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant submittedAt;
    private Instant decidedAt;
    private UUID decidedBy;
    private String decidedByName;
    private String decisionNote;
}
