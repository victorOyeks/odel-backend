package com.odel.messaging;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/** An item in the in-app bell. */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID userId;
    private String type;
    private String title;
    private String body;
    private String link;
    private Instant createdAt;
    private Instant readAt;
}
