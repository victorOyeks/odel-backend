package com.odel.notification.repository;

import org.springframework.data.jpa.repository.Query;

import com.odel.notification.entity.PushToken;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;
import com.odel.notification.service.jobs.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushTokenRepository extends JpaRepository<PushToken, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Query("select x from PushToken x where x.id = ?1")
    Optional<PushToken> findInTenant(UUID id);

    List<PushToken> findByUserId(UUID userId);
    Optional<PushToken> findByToken(String token);
}
