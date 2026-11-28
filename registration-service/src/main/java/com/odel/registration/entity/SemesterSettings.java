package com.odel.registration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A semester's course-change deadline (A-05 "Course changes allowed until"). */
@Entity
@Table(name = "semester_settings")
@IdClass(SemesterSettings.Key.class)
@Getter
@Setter
public class SemesterSettings {
    public record Key(UUID tenantId, UUID semesterId) implements java.io.Serializable {
        public Key() { this(null, null); }
    }

    /** The university (its own key; read with the current tenant's id, never with an id from a request). */
    @Id
    private UUID tenantId;
    @Id
    private UUID semesterId;
    private LocalDate changesCloseOn;
    private Instant updatedAt;
}
