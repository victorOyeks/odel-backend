package com.odel.student.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A student of one university, as imported from its portal (W-18) or added by hand (W-22c). */
@Entity
@Table(name = "students")
@Getter
@Setter
public class Student {
    public enum Status {
        IMPORTED("Not activated"), INVITED("Not activated"), ACTIVE("Active"), SUSPENDED("Suspended");

        private final String label;

        Status(String label) { this.label = label; }

        public String label() { return label; }
    }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID userId;
    private String matricNumber;
    private String surname;
    private String firstName;
    private String otherNames;
    private String email;
    private String phone;
    private String gender;
    private LocalDate dateOfBirth;
    private UUID programmeId;
    private String programmeCode;
    private String programmeName;
    private int level;
    private UUID centreId;
    private String centreCode;
    private String centreName;
    private String centreShortName;
    private String entrySession;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Enumerated(EnumType.STRING)
    private Status statusBeforeSuspension;
    private Instant welcomeSentAt;
    private Instant activatedAt;
    private String consentVersion;
    private Instant consentAt;
    private UUID importId;
    private Instant createdAt;
    private Instant updatedAt;
    // ---- B3 profile (changelog 003) ----
    private String addressLine;
    private String addressTown;
    private String addressState;
    private String nextOfKinName;
    private String nextOfKinRelationship;
    private String nextOfKinPhone;
    private String photoKey;
    private Instant photoUpdatedAt;

    public String fullName() {
        return firstName + (otherNames == null || otherNames.isBlank() ? "" : " " + otherNames) + " " + surname;
    }

    /** "Adeola Okafor": how lists and emails name the student. */
    public String displayName() {
        return firstName + " " + surname;
    }

    public String initials() {
        return (firstName.substring(0, 1) + surname.substring(0, 1)).toUpperCase();
    }
}
