package com.odel.identity;

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
