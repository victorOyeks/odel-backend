package com.odel.academic.repository;

import com.odel.academic.entity.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped through @TenantId: queries only ever see the current university. */
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Override
    @Query("select x from ImportJob x where x.id = ?1")
    Optional<ImportJob> findById(UUID id);

}
