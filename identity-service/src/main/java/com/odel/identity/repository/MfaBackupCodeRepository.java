package com.odel.identity.repository;

import java.util.Optional;

import com.odel.identity.entity.MfaBackupCode;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Query("select x from MfaBackupCode x where x.id = ?1")
    Optional<MfaBackupCode> findInTenant(UUID id);

    List<MfaBackupCode> findByUserIdAndUsedAtIsNull(UUID userId);

    List<MfaBackupCode> findByUserId(UUID userId);

    @Modifying
    @Query("delete from MfaBackupCode c where c.userId = ?1")
    void deleteByUserId(UUID userId);
}
