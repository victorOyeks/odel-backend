package com.odel.identity.repository;

import com.odel.identity.entity.TrustedDevice;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.UUID;

/** Clears a person's two-step state when an ODeL admin resets it. */
public interface TwoStepRepository extends Repository<TrustedDevice, UUID> {
    @Modifying
    @Query("delete from TrustedDevice d where d.userId = ?1")
    int deleteTrustedDevices(UUID userId);

    @Modifying
    @Query("delete from MfaBackupCode c where c.userId = ?1")
    int deleteBackupCodes(UUID userId);
}
