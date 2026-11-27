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
public interface CourseProgrammeRepository extends JpaRepository<CourseProgramme, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Override
    @Query("select x from CourseProgramme x where x.id = ?1")
    Optional<CourseProgramme> findById(UUID id);

    List<CourseProgramme> findByCourseId(UUID courseId);

    List<CourseProgramme> findByProgrammeId(UUID programmeId);

    @Modifying
    @Query("delete from CourseProgramme c where c.courseId = ?1")
    void deleteByCourseId(UUID courseId);
}
