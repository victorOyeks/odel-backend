package com.odel.student.repository;

import com.odel.student.entity.ProfileChange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Tenant-scoped: every query only sees the current university's history. */
public interface ProfileChangeRepository extends JpaRepository<ProfileChange, UUID> {
    Page<ProfileChange> findByStudentIdOrderByCreatedAtDesc(UUID studentId, Pageable page);
}
