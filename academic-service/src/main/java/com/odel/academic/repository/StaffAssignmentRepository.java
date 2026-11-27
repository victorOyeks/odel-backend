package com.odel.academic.repository;

import com.odel.academic.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped through @TenantId: queries only ever see the current university. */
public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, UUID> {
    List<StaffAssignment> findBySessionId(UUID sessionId);

    List<StaffAssignment> findBySessionIdAndCourseId(UUID sessionId, UUID courseId);

    List<StaffAssignment> findBySessionIdAndCentreIdAndKind(UUID sessionId, UUID centreId, StaffAssignment.Kind kind);

    List<StaffAssignment> findByUserId(UUID userId);

    boolean existsBySessionId(UUID sessionId);
}
