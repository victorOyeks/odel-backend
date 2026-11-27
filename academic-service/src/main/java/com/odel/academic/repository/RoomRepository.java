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
public interface RoomRepository extends JpaRepository<Room, UUID> {
    /** By id within the current university: a JPQL query, because Hibernate's find() by id skips the @TenantId filter. */
    @Override
    @Query("select x from Room x where x.id = ?1")
    Optional<Room> findById(UUID id);

    @Query("select r from Room r where r.centreId = ?1 order by lower(r.name)")
    List<Room> findByCentreId(UUID centreId);

    @Query("select r from Room r where r.centreId = ?1 and lower(r.name) = lower(?2)")
    Optional<Room> findByCentreAndName(UUID centreId, String name);
}
