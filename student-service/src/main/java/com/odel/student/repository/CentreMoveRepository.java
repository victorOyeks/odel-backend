package com.odel.student.repository;

import com.odel.student.entity.CentreMove;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped: every query only sees the current university's moves. */
public interface CentreMoveRepository extends JpaRepository<CentreMove, UUID> {
    @Query("select m from CentreMove m where m.id = ?1")
    Optional<CentreMove> findInTenant(UUID id);

    List<CentreMove> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    Optional<CentreMove> findFirstByStudentIdAndStatus(UUID studentId, CentreMove.Status status);
}
