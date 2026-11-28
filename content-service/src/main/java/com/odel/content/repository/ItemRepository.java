package com.odel.content.repository;

import com.odel.content.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lists leave out replacement uploads (replacesItemId set): they are not items yet. */
public interface ItemRepository extends JpaRepository<Item, UUID> {
    @Query("select i from Item i where i.id = ?1")
    Optional<Item> findInTenant(UUID id);

    @Query("select i from Item i where i.weekId = ?1 and i.replacesItemId is null order by i.position")
    List<Item> forWeek(UUID weekId);

    @Query("select i from Item i where i.offeringId = ?1 and i.replacesItemId is null order by i.position")
    List<Item> forOffering(UUID offeringId);

    @Query("select i from Item i where i.offeringId in ?1 and i.replacesItemId is null")
    List<Item> forOfferings(Collection<UUID> offeringIds);

    @Query("select i from Item i where i.replacesItemId = ?1")
    List<Item> replacementsOf(UUID itemId);

    @Query("select coalesce(max(i.position), 0) from Item i where i.weekId = ?1 and i.replacesItemId is null")
    int lastPosition(UUID weekId);

    @Query("select count(i) from Item i where i.weekId = ?1 and i.replacesItemId is null")
    long countInWeek(UUID weekId);
}
