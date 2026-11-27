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
public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    @Query("select d from Department d order by lower(d.name)")
    List<Department> sorted();

    @Query("select d from Department d where upper(d.code) = upper(?1)")
    Optional<Department> findByCode(String code);

    @Query("select d from Department d where d.facultyId = ?1 and lower(d.name) = lower(?2)")
    Optional<Department> findByFacultyAndName(UUID facultyId, String name);

    List<Department> findByFacultyId(UUID facultyId);
}
