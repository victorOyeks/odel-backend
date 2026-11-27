package com.odel.student.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/** One change of a student's status (imported, invited, activated, suspended, reinstated), with who did it. */
@Entity
@Table(name = "student_status_history")
@Getter
@Setter
public class StudentStatusChange {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID studentId;
    @Enumerated(EnumType.STRING)
    private Student.Status fromStatus;
    @Enumerated(EnumType.STRING)
    private Student.Status toStatus;
    private String reason;
    private UUID actorUserId;
    private String actorName;
    private Instant createdAt;
}
