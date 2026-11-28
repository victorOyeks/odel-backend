package com.odel.content.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One student's progress on one item: resume position, watch time, the 10-second stretches watched, done. */
@Entity
@Table(name = "item_progress")
@Getter
@Setter
public class ItemProgress {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID offeringId;
    private UUID itemId;
    private UUID studentId;
    private UUID userId;
    private int positionSeconds;
    private int watchedSeconds;
    private String buckets;
    private int percentWatched;
    private boolean done;
    private Instant doneAt;
    private Instant openedAt;
    private Instant lastSeenAt;
}
