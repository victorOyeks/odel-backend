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

/** "Trust this computer for 30 days" (W-03): this browser skips the two-step code until it expires. */
@Entity
@Table(name = "trusted_devices")
@Getter
@Setter
public class TrustedDevice {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID userId;
    private String tokenHash;
    private String userAgent;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant lastUsedAt;
}
