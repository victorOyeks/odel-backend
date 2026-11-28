package com.odel.content.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A message to a course's students (bell, push and email). */
@Entity
@Table(name = "announcements")
@Getter
@Setter
public class Announcement {
    public enum Audience { ALL, INACTIVE, STUDENTS }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID offeringId;
    private String title;
    private String body;
    private UUID authorUserId;
    private String authorName;
    @Enumerated(EnumType.STRING)
    private Audience audience;
    private int recipients;
    private boolean email;
    private Instant postedAt;
    private Instant editedAt;
}
