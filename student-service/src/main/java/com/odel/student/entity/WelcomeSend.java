package com.odel.student.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/** One sending of the welcome email: to every student not yet active (ALL), to one (SINGLE), or the reminder. */
@Entity
@Table(name = "welcome_sends")
@Getter
@Setter
public class WelcomeSend {
    public enum Kind { ALL, SINGLE, REMINDER }

    public enum Status { RUNNING, DONE }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String subject;
    private String body;
    private Integer remindAfterDays;
    private Instant reminderDueAt;
    private Instant reminderSentAt;
    private UUID parentSendId;
    private int totalStudents;
    private int skippedActive;
    private UUID requestedBy;
    private String requestedByName;
    private Instant startedAt;
    private Instant finishedAt;
}
