package com.odel.identity.entity;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    /**
     * GOOGLE = a staff account (the name is historical: staff now prove who they are with Google, Microsoft or an emailed
     * code, as their university chose; the account is tied to the email, not the method). PASSWORD = a student account.
     */
    public enum AuthType { GOOGLE, PASSWORD }
    public enum Status { INVITED, ACTIVE, SUSPENDED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private String email;
    private String fullName;
    private String phone;
    private String staffNumber;
    @Enumerated(EnumType.STRING)
    private AuthType authType;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String mfaSecretEnc;
    private Instant mfaEnabledAt;
    private UUID lastRoleAssignmentId;
    private Instant invitedAt;
    private Instant acceptedAt;
    private Instant lastSignInAt;
    private Instant suspendedAt;
    private String suspendedReason;
    private UUID invitedBy;
    private Instant createdAt;
    private Instant updatedAt;

    // ---- students (B1, changelog 003) ----
    private String matricNumber;
    private UUID studentId;
    private String passwordHash;
    private Instant passwordChangedAt;
    private String consentVersion;
    private Instant consentAt;
    private int failedSignIns;
    private Instant lockedUntil;

    /** A staff (or provider) account: signs in through the staff sign-in, never with a password. */
    public boolean isStaff() {
        return authType == AuthType.GOOGLE;
    }

    public boolean isStudent() {
        return matricNumber != null && authType == AuthType.PASSWORD;
    }

    public String firstName() {
        String[] parts = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[0];
    }

    public boolean mfaEnabled() {
        return mfaEnabledAt != null && mfaSecretEnc != null;
    }

    /** Titles left out of the initials: "Mrs Bisi Ade" → BA, "Dr Funmi Bello" → FB. */
    private static final java.util.Set<String> TITLES = java.util.Set.of("mr", "mrs", "ms", "miss", "dr", "prof", "engr", "chief", "rev",
            "barr", "arc", "pastor", "alhaji", "alhaja", "hajia", "sir", "dame", "pharm");

    public String initials() {
        String[] all = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        String[] parts = java.util.Arrays.stream(all).filter(w -> !TITLES.contains(w.replace(".", "").toLowerCase(java.util.Locale.ROOT))).toArray(String[]::new);
        if (parts.length == 0) parts = all;
        if (parts.length == 0 || parts[0].isEmpty()) return "?";
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }
}
