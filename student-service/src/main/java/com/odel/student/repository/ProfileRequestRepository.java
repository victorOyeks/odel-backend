package com.odel.student.repository;

import com.odel.student.entity.ProfileRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped: every query only sees the current university's requests. */
public interface ProfileRequestRepository extends JpaRepository<ProfileRequest, UUID>, JpaSpecificationExecutor<ProfileRequest> {
    @Query("select r from ProfileRequest r where r.id = ?1")
    Optional<ProfileRequest> findInTenant(UUID id);

    List<ProfileRequest> findByStudentIdOrderBySubmittedAtDesc(UUID studentId);

    List<ProfileRequest> findByStudentIdAndStatus(UUID studentId, ProfileRequest.Status status);

    long countByStatus(ProfileRequest.Status status);

    long countByStatusAndKind(ProfileRequest.Status status, ProfileRequest.Kind kind);
}
