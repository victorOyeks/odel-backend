package com.odel.messaging;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "push_tokens")
@Getter
@Setter
public class PushToken {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID userId;
    private String token;
    private String platform;
    private Instant createdAt;
    private Instant lastSeenAt;
}
