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
public interface SemesterRepository extends JpaRepository<Semester, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Override
    @Query("select x from Semester x where x.id = ?1")
    Optional<Semester> findById(UUID id);

    List<Semester> findBySessionIdOrderByNumber(UUID sessionId);

    List<Semester> findBySessionIdIn(Collection<UUID> sessionIds);

    @Modifying
    @Query("delete from Semester s where s.sessionId = ?1")
    void deleteBySessionId(UUID sessionId);
}
