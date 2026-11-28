package com.odel.content.repository;

import com.odel.content.entity.ItemProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProgressRepository extends JpaRepository<ItemProgress, UUID> {
    @Query("select p from ItemProgress p where p.itemId = ?1 and p.studentId = ?2")
    Optional<ItemProgress> find(UUID itemId, UUID studentId);

    @Query("select p from ItemProgress p where p.offeringId = ?1 and p.studentId = ?2")
    List<ItemProgress> forStudent(UUID offeringId, UUID studentId);

    @Query("select p from ItemProgress p where p.offeringId in ?1 and p.studentId = ?2")
    List<ItemProgress> forStudentIn(Collection<UUID> offeringIds, UUID studentId);

    @Query("select p from ItemProgress p where p.offeringId = ?1")
    List<ItemProgress> forOffering(UUID offeringId);

    @Query("select p from ItemProgress p where p.studentId = ?1 and p.offeringId in ?2 and p.done = false and p.positionSeconds > 0 order by p.lastSeenAt desc")
    List<ItemProgress> unfinished(UUID studentId, Collection<UUID> offeringIds);

    @Query("select count(distinct p.studentId) from ItemProgress p where p.itemId = ?1 and p.openedAt is not null")
    long openedBy(UUID itemId);
}
