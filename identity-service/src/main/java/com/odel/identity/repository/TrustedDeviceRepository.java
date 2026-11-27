package com.odel.identity.repository;

import org.springframework.data.jpa.repository.Query;

import com.odel.identity.entity.TrustedDevice;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Query("select x from TrustedDevice x where x.id = ?1")
    Optional<TrustedDevice> findInTenant(UUID id);

    Optional<TrustedDevice> findByTokenHash(String tokenHash);
}
