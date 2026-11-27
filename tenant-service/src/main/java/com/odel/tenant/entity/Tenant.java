package com.odel.tenant.entity;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

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
    /** The university's own domain: none, waiting for its DNS record, pointing at us (certificate next), or live. */
    public enum WebAddressStatus { NONE, WAITING_DNS, DNS_FOUND, LIVE }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String slug;
    private String name;
    private String shortName;
    private String webAddress;
    @Enumerated(EnumType.STRING)
    private WebAddressStatus webAddressStatus = WebAddressStatus.NONE;
    private Instant webAddressCheckedAt;
    private String webAddressError;
    private Instant webAddressCertAttemptAt;
    private Instant webAddressLiveAt;
    private String googleDomain;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String primaryColor;
    private String accentColor;
    private String logoKey;
    private Instant createdAt;
    private UUID createdBy;
    private String createdByName;
    private Instant updatedAt;

    public boolean isPlatform() {
        return kind == Kind.PLATFORM;
    }
}
