package com.odel.content.repository;

import com.odel.content.entity.Offering;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped through @TenantId. Lookups by id go through findInTenant: Hibernate's find() by id skips the filter. */
public interface OfferingRepository extends JpaRepository<Offering, UUID> {
    @Query("select o from Offering o where o.id = ?1")
    Optional<Offering> findInTenant(UUID id);

    @Query("select o from Offering o where o.courseId = ?1 and o.semesterId = ?2")
    Optional<Offering> find(UUID courseId, UUID semesterId);

    @Query("select o from Offering o where o.semesterId = ?1")
    List<Offering> forSemester(UUID semesterId);

    @Query("select o from Offering o where o.courseId = ?1 order by o.sessionName desc, o.semesterNumber desc")
    List<Offering> forCourse(UUID courseId);
}
