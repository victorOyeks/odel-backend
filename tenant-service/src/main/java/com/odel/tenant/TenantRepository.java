package com.odel.tenant;

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
    List<Tenant> findByKindOrderByCreatedAtDesc(Tenant.Kind kind);
}
