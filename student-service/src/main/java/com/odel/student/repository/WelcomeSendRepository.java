package com.odel.student.repository;

import com.odel.student.entity.WelcomeSend;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WelcomeSendRepository extends JpaRepository<WelcomeSend, UUID> {
    Optional<WelcomeSend> findFirstByKindAndStatus(WelcomeSend.Kind kind, WelcomeSend.Status status);

    Optional<WelcomeSend> findFirstByKindOrderByStartedAtDesc(WelcomeSend.Kind kind);

    Page<WelcomeSend> findByKindInOrderByStartedAtDesc(java.util.Collection<WelcomeSend.Kind> kinds, Pageable page);
}
