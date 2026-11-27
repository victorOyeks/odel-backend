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
public interface ProgrammeRepository extends JpaRepository<Programme, UUID> {
    @Query("select p from Programme p order by lower(p.name)")
    List<Programme> sorted();

    @Query("select p from Programme p where upper(p.code) = upper(?1)")
    Optional<Programme> findByCode(String code);

    @Query("select p from Programme p where p.departmentId = ?1 and lower(p.name) = lower(?2)")
    Optional<Programme> findByDepartmentAndName(UUID departmentId, String name);

    List<Programme> findByDepartmentId(UUID departmentId);
}
