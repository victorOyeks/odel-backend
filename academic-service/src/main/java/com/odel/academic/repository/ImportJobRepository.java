package com.odel.academic.repository;

import com.odel.academic.entity.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Tenant-scoped through @TenantId: queries only ever see the current university. */
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
}
