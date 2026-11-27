package com.odel.identity;

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

    public String initials() {
        String[] parts = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return "?";
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }
}
