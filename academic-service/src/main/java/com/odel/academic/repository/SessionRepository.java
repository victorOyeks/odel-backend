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
public interface SessionRepository extends JpaRepository<AcademicSession, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Override
    @Query("select x from AcademicSession x where x.id = ?1")
    Optional<AcademicSession> findById(UUID id);

    @Query("select s from AcademicSession s order by s.startsOn desc nulls last, s.createdAt desc")
    List<AcademicSession> newestFirst();

    @Query("select s from AcademicSession s where lower(s.name) = lower(?1)")
    Optional<AcademicSession> findByName(String name);

    Optional<AcademicSession> findFirstByStatus(AcademicSession.Status status);
}
