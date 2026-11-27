package com.odel.identity.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Query;

import com.odel.identity.entity.RoleAssignment;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Query("select x from RoleAssignment x where x.id = ?1")
    Optional<RoleAssignment> findInTenant(UUID id);

    List<RoleAssignment> findByUserIdOrderByCreatedAt(UUID userId);
    boolean existsByRole(Role role);
}
