package com.odel.registration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The university's registration rules that are not per level (A-05 "Fee rules"). One row per university, created on first save. */
@Entity
@Table(name = "settings")
@Getter
@Setter
public class Settings {
    /** The university (its own key; read with the current tenant's id, never with an id from a request). */
    @Id
    private UUID tenantId;
    private boolean blockRegistration;
    private boolean blockExams;
    private boolean previewsWhenUnpaid;
    private Instant updatedAt;
    private String updatedByName;
}
