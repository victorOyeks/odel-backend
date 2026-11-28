package com.odel.payment.service;

import com.odel.common.ApiException;
import com.odel.common.tenancy.TenantContext;
import com.odel.payment.client.PeerClient;
import com.odel.payment.dto.PaymentDtos.FeeStatus;
import com.odel.payment.dto.PaymentDtos.FeeStatusesRequest;
import com.odel.payment.dto.PaymentDtos.InternalSummary;
import com.odel.payment.dto.PaymentDtos.Items;
import com.odel.payment.dto.PeerDtos.Semester;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.repository.FeeRepository;
import com.odel.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fee status for other services: registration's fee block (and later exam eligibility), dashboards. */
@Service
public class InternalService {
    private final PeerClient peers;
    private final FeeService feeService;
    private final FeeRepository fees;
    private final PaymentRepository payments;
    private final TransactionTemplate tx;

    public InternalService(PeerClient peers, FeeService feeService, FeeRepository fees, PaymentRepository payments, TransactionTemplate tx) {
        this.peers = peers;
        this.feeService = feeService;
        this.fees = fees;
        this.payments = payments;
        this.tx = tx;
    }

    private FeeStatus status(UUID studentId, Semester sem, Fee fee, Payment open) {
        if (fee == null) {
            long kobo = feeService.settings(TenantContext.require()).feeKobo();
            return new FeeStatus(studentId, sem.id(), sem.label(), "NOT_PAID", false, null, null, Money.naira(kobo));
        }
        String s = FeeLedger.effectiveStatus(fee, open);
        Payment paid = fee.getStatus() == Fee.Status.PAID && fee.getPaidPaymentId() != null ? payments.findInTenant(fee.getPaidPaymentId()).orElse(null) : null;
        return new FeeStatus(studentId, sem.id(), fee.label(), s, "PAID".equals(s), fee.getStatus() == Fee.Status.PAID ? fee.getPaidAt() : null,
                paid == null ? null : paid.getReference(), Money.naira(fee.getAmountKobo()));
    }

    public FeeStatus feeStatus(UUID studentId, UUID userId, UUID semesterId) {
        if (studentId == null && userId == null) throw ApiException.badRequest("invalid", "Give studentId or userId.");
        UUID sid = studentId != null ? studentId : peers.studentByUser(userId).id();
        Semester sem = FeeService.pick(peers.semesters(), semesterId);
        return tx.execute(t -> {
            Fee fee = fees.findByStudentIdAndSemesterId(sid, sem.id()).orElse(null);
            return status(sid, sem, fee, fee == null ? null : payments.findOpen(fee.getId()).orElse(null));
        });
    }

    public Items<FeeStatus> feeStatuses(FeeStatusesRequest in) {
        Semester sem = FeeService.pick(peers.semesters(), in == null ? null : in.semesterId());
        List<UUID> ids = in == null || in.studentIds() == null ? List.of() : in.studentIds();
        return tx.execute(t -> {
            Map<UUID, Fee> bySt = ids.isEmpty() ? Map.of() : fees.findBySemesterIdAndStudentIdIn(sem.id(), ids).stream()
                    .collect(Collectors.toMap(Fee::getStudentId, Function.identity()));
            Map<UUID, Payment> open = new HashMap<>();
            if (!bySt.isEmpty()) {
                for (Payment p : payments.findByFeeIds(bySt.values().stream().map(Fee::getId).toList())) {
                    if (p.getStatus().open()) open.put(p.getFeeId(), p);
                }
            }
            return new Items<>(ids.stream().map(id -> {
                Fee f = bySt.get(id);
                return status(id, sem, f, f == null ? null : open.get(f.getId()));
            }).toList());
        });
    }

    public InternalSummary summary(UUID semesterId) {
        Semester sem = FeeService.pick(peers.semesters(), semesterId);
        return tx.execute(t -> {
            List<Fee> all = fees.findBySemesterId(sem.id());
            Set<UUID> confirmingFees = all.isEmpty() ? Set.of() : payments.findByFeeIds(all.stream().map(Fee::getId).toList()).stream()
                    .filter(p -> p.getStatus() == Payment.Status.CONFIRMING).map(Payment::getFeeId).collect(Collectors.toSet());
            long paid = all.stream().filter(f -> f.getStatus() == Fee.Status.PAID).count();
            long confirming = all.stream().filter(f -> f.getStatus() != Fee.Status.PAID && confirmingFees.contains(f.getId())).count();
            long refunded = all.stream().filter(f -> f.getStatus() == Fee.Status.REFUNDED).count();
            long reversed = all.stream().filter(f -> f.getStatus() == Fee.Status.REVERSED).count();
            return new InternalSummary(sem.id(), paid, confirming, refunded, reversed,
                    all.stream().filter(f -> f.getStatus() == Fee.Status.PAID).map(Fee::getStudentId).toList());
        });
    }
}
