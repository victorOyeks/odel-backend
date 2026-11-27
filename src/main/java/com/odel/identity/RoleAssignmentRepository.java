package com.odel.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {
    List<RoleAssignment> findByUserIdOrderByCreatedAt(UUID userId);
    boolean existsByRole(Role role);
}
