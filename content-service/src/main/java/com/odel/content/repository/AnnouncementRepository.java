package com.odel.content.repository;

import com.odel.content.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {
    @Query("select a from Announcement a where a.id = ?1")
    Optional<Announcement> findInTenant(UUID id);

    @Query("select a from Announcement a where a.offeringId = ?1 order by a.postedAt desc")
    List<Announcement> forOffering(UUID offeringId);
}
