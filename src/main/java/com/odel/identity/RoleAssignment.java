package com.odel.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/** One role a person holds, with its scope (a centre, a department, a course...). People can hold several. */
@Entity
@Table(name = "role_assignments")
@Getter
@Setter
public class RoleAssignment {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private Role role;
    @Enumerated(EnumType.STRING)
    private ScopeType scopeType;
    private UUID scopeId;
    private String scopeLabel;
    private Instant createdAt;
    private UUID createdBy;
}
