package com.odel.identity.repository;

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
    List<RoleAssignment> findByUserIdOrderByCreatedAt(UUID userId);
    boolean existsByRole(Role role);
}
