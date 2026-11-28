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
public interface CourseRepository extends JpaRepository<Course, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Override
    @Query("select x from Course x where x.id = ?1")
    Optional<Course> findById(UUID id);

    @Query("select c from Course c where upper(replace(c.code, ' ', '')) = upper(replace(?1, ' ', ''))")
    Optional<Course> findByCode(String code);

    /** Several by id within the current university (registration-service's carry-overs and slips). */
    @Query("select c from Course c where c.id in ?1")
    List<Course> findAllInTenant(java.util.Collection<UUID> ids);

    @Query("select c from Course c order by c.code")
    List<Course> sorted();
}
