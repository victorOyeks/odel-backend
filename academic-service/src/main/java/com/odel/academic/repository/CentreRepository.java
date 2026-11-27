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
public interface CentreRepository extends JpaRepository<Centre, UUID> {
    @Query("select c from Centre c order by lower(c.name)")
    List<Centre> sorted();

    @Query("select c from Centre c where upper(c.code) = upper(?1)")
    Optional<Centre> findByCode(String code);

    @Query("select c from Centre c where lower(c.name) = lower(?1)")
    Optional<Centre> findByName(String name);
}
