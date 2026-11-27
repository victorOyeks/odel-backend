package com.odel.student.repository;

import com.odel.student.entity.StudentImport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface StudentImportRepository extends JpaRepository<StudentImport, UUID> {
    /** By id inside the current university (the inherited findById skips the @TenantId filter). */
    @Query("select i from StudentImport i where i.id = ?1")
    Optional<StudentImport> findInTenant(UUID id);
    Page<StudentImport> findByStatusOrderByImportedAtDesc(StudentImport.Status status, Pageable page);

    Optional<StudentImport> findFirstByStatusOrderByImportedAtDesc(StudentImport.Status status);
}
