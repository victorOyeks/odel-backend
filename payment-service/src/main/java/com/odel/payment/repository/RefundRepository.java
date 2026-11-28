package com.odel.payment.repository;

import com.odel.payment.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped through @TenantId. */
public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByPaymentId(UUID paymentId);

    List<Refund> findByPaymentIdIn(Collection<UUID> paymentIds);

    List<Refund> findByStatus(Refund.Status status);

    /** References are unique across universities (the database index is global). */
    @org.springframework.data.jpa.repository.Query(value = "select count(*) from refunds where reference = ?1", nativeQuery = true)
    long countByReferenceAnywhere(String reference);
}
