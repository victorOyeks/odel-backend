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
@Table(name = "mfa_backup_codes")
@Getter
@Setter
public class MfaBackupCode {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID userId;
    private String codeHash;
    private Instant usedAt;
    private Instant createdAt;
}
