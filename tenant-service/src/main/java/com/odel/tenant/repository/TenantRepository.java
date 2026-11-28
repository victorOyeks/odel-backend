package com.odel.tenant.repository;

import com.odel.tenant.entity.Tenant;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);
    Optional<Tenant> findByWebAddressIgnoreCase(String webAddress);
    boolean existsBySlug(String slug);
    boolean existsByWebAddressIgnoreCase(String webAddress);
    /** Names compare without case and with runs of spaces treated as one (QA ODEL-B0-002). */
    @org.springframework.data.jpa.repository.Query(value = "select * from tenants where lower(regexp_replace(trim(name), '\\s+', ' ', 'g')) = lower(:name) limit 1", nativeQuery = true)
    Optional<Tenant> findByNormalisedName(@org.springframework.data.repository.query.Param("name") String name);
    List<Tenant> findByKindOrderByCreatedAtDesc(Tenant.Kind kind);
}
