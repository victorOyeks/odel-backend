package com.odel.identity.repository;

import com.odel.identity.entity.User;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped: every query only sees the current university's users. */
public interface UserRepository extends JpaRepository<User, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Query("select x from User x where x.id = ?1")
    Optional<User> findInTenant(UUID id);

    @Query("select u from User u where lower(u.email) = lower(?1)")
    Optional<User> findByEmail(String email);

    Optional<User> findByMatricNumber(String matricNumber);
}
