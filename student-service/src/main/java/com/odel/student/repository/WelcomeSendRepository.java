package com.odel.student.repository;

import com.odel.student.entity.WelcomeSend;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface WelcomeSendRepository extends JpaRepository<WelcomeSend, UUID> {
    /** By id inside the current university (the inherited findById skips the @TenantId filter). */
    @Query("select w from WelcomeSend w where w.id = ?1")
    Optional<WelcomeSend> findInTenant(UUID id);

    @Query("select w from WelcomeSend w where w.id in ?1")
    java.util.List<WelcomeSend> findAllInTenant(java.util.Collection<UUID> ids);
    Optional<WelcomeSend> findFirstByKindAndStatus(WelcomeSend.Kind kind, WelcomeSend.Status status);

    Optional<WelcomeSend> findFirstByKindOrderByStartedAtDesc(WelcomeSend.Kind kind);

    Page<WelcomeSend> findByKindInOrderByStartedAtDesc(java.util.Collection<WelcomeSend.Kind> kinds, Pageable page);
}
