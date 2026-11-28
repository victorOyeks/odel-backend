package com.odel.payment.repository;

import com.odel.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped through @TenantId. Lookups by id use findInTenant: Hibernate's find() by id skips the tenant filter. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    @Query("select p from Payment p where p.id = ?1")
    Optional<Payment> findInTenant(UUID id);

    Optional<Payment> findByReference(String reference);

    /** References are unique across universities (the database index is global, and Paystack's account is shared). */
    @Query(value = "select count(*) from payments where reference = ?1", nativeQuery = true)
    long countByReferenceAnywhere(String reference);

    List<Payment> findByFeeIdOrderByCreatedAtDesc(UUID feeId);

    List<Payment> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    @Query("select p from Payment p where p.feeId in ?1 order by p.createdAt desc")
    List<Payment> findByFeeIds(Collection<UUID> feeIds);

    @Query("select p from Payment p where p.feeId = ?1 and p.status in (com.odel.payment.entity.Payment.Status.PENDING, com.odel.payment.entity.Payment.Status.CONFIRMING)")
    Optional<Payment> findOpen(UUID feeId);
}
