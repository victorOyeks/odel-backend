package com.odel.content.repository;

import com.odel.content.entity.Week;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WeekRepository extends JpaRepository<Week, UUID> {
    @Query("select w from Week w where w.id = ?1")
    Optional<Week> findInTenant(UUID id);

    @Query("select w from Week w where w.offeringId = ?1 order by w.number")
    List<Week> forOffering(UUID offeringId);

    @Query("select w from Week w where w.offeringId in ?1 order by w.number")
    List<Week> forOfferings(Collection<UUID> offeringIds);

    @Query("select coalesce(max(w.number), 0) from Week w where w.offeringId = ?1")
    int lastNumber(UUID offeringId);
}
