package com.odel.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, UUID> {
    List<MfaBackupCode> findByUserIdAndUsedAtIsNull(UUID userId);

    @Modifying
    @Query("delete from MfaBackupCode c where c.userId = ?1")
    void deleteByUserId(UUID userId);
}
