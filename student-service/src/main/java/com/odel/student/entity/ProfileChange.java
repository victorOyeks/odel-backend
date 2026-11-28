package com.odel.student.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/** One change to a student's record, kept for the record's history (W-22b "Changes"): what, old and new value, who and how. */
@Entity
@Table(name = "profile_changes")
@Getter
@Setter
public class ProfileChange {
    public enum Source {
        STUDENT("By the student"), REQUEST("Approved by the ODeL office"), OFFICE("By the ODeL office");

        private final String label;

        Source(String label) { this.label = label; }

        public String label() { return label; }
    }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID studentId;
    private String field;
    private String oldValue;
    private String newValue;
    @Enumerated(EnumType.STRING)
    private Source source;
    private UUID requestId;
    private UUID actorUserId;
    private String actorName;
    private Instant createdAt;
}
