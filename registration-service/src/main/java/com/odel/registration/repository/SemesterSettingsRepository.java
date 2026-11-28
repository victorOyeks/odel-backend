package com.odel.registration.repository;

import com.odel.registration.entity.SemesterSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/** Keyed by university and semester: always read with TenantContext.require(). */
public interface SemesterSettingsRepository extends JpaRepository<SemesterSettings, SemesterSettings.Key> {
    @Query("select s from SemesterSettings s where s.tenantId = ?1 and s.semesterId = ?2")
    Optional<SemesterSettings> forSemester(UUID tenantId, UUID semesterId);
}
