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
public interface FacultyRepository extends JpaRepository<Faculty, UUID> {
    @Query("select f from Faculty f order by lower(f.name)")
    List<Faculty> sorted();

    @Query("select f from Faculty f where upper(f.code) = upper(?1)")
    Optional<Faculty> findByCode(String code);

    @Query("select f from Faculty f where lower(f.name) = lower(?1)")
    Optional<Faculty> findByName(String name);
}
