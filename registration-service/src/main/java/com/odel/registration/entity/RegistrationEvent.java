package com.odel.registration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The registration's history: submitted, then each approved change (with the slip version it produced). */
@Entity
@Table(name = "registration_events")
@Getter
@Setter
public class RegistrationEvent {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID registrationId;
    private String action;
    private String summary;
    private int slipVersion;
    private String actorName;
    private String courses;
    private Instant createdAt;
}
