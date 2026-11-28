package com.odel.payment.repository;

import com.odel.payment.entity.Fee;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped through @TenantId. Lookups by id use findInTenant: Hibernate's find() by id skips the tenant filter. */
public interface FeeRepository extends JpaRepository<Fee, UUID> {
    @Query("select f from Fee f where f.id = ?1")
    Optional<Fee> findInTenant(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Fee f where f.id = ?1")
    Optional<Fee> lockInTenant(UUID id);

    Optional<Fee> findByStudentIdAndSemesterId(UUID studentId, UUID semesterId);

    List<Fee> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    List<Fee> findBySemesterId(UUID semesterId);

    List<Fee> findBySemesterIdAndStudentIdIn(UUID semesterId, Collection<UUID> studentIds);
}
