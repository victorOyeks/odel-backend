package com.odel.registration.repository;

import com.odel.registration.entity.Settings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Keyed by the university's id: always read with TenantContext.require(). */
public interface SettingsRepository extends JpaRepository<Settings, UUID> {
}
