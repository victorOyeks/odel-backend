package com.odel.tenancy;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A university on the platform (or the platform itself). Not tenant-scoped: it is the tenant. */
@Entity
@Table(name = "tenants")
@Getter
@Setter
public class Tenant {
    public enum Kind { PLATFORM, UNIVERSITY }
    public enum Status { SETTING_UP, LIVE, SUSPENDED }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String slug;
    private String name;
    private String shortName;
    private String webAddress;
    private String googleDomain;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String primaryColor;
    private String accentColor;
    private String logoKey;
    private Instant createdAt;
    private UUID createdBy;
    private Instant updatedAt;

    public boolean isPlatform() {
        return kind == Kind.PLATFORM;
    }
}
