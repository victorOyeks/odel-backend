package com.odel.payment.service;

import com.odel.common.ApiException;
import com.odel.common.tenancy.TenantContext;
import com.odel.payment.config.PaymentProperties;
import com.odel.payment.dto.PeerDtos.Semester;
import com.odel.payment.dto.PeerDtos.Semesters;
import com.odel.payment.dto.PeerDtos.Student;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.entity.Refund;
import com.odel.payment.repository.FeeRepository;
import com.odel.payment.repository.FeeSettingsRepository;
import com.odel.payment.repository.FeeSettingsRepository.Settings;
import com.odel.payment.repository.PaymentRepository;
import com.odel.payment.repository.RefundRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Which fee a student owes (one per semester), its amount from the university's settings, and lookups shared by the screens. */
@Service
public class FeeService {
    private final FeeRepository fees;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final FeeSettingsRepository settings;
    private final PaymentProperties props;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FeeService(FeeRepository fees, PaymentRepository payments, RefundRepository refunds, FeeSettingsRepository settings,
                      PaymentProperties props, JdbcTemplate jdbc, Clock clock) {
        this.fees = fees;
        this.payments = payments;
        this.refunds = refunds;
        this.settings = settings;
        this.props = props;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The given semester of this university, or the current one. 404 no_session when there is none. */
    public static Semester pick(Semesters all, UUID semesterId) {
        if (all == null || all.items() == null) throw noSession();
        if (semesterId != null) {
            return all.items().stream().filter(s -> s.id().equals(semesterId)).findFirst().orElseThrow(ApiException::notFound);
        }
        if (all.currentSemesterId() == null) throw noSession();
        return all.items().stream().filter(s -> s.id().equals(all.currentSemesterId())).findFirst().orElseThrow(FeeService::noSession);
    }

    static ApiException noSession() {
        return new ApiException(HttpStatus.NOT_FOUND, "no_session", "Your university has not opened a session yet.");
    }

    /** The university's fee and centre share in kobo: its own settings, or the defaults. */
    public Settings settings(UUID tenantId) {
        return settings.find(tenantId).orElse(new Settings(tenantId, props.defaultFee() * 100, props.defaultCentreShare() * 100,
                null, null, null, null, null, null, null, null, null));
    }

    /** The student's fee for a semester, created on first sight. Call inside a transaction. */
    public Fee ensure(Student st, Semester sem) {
        Settings s = settings(TenantContext.require());
        Instant now = clock.instant();
        jdbc.update("""
                insert into fees (id, tenant_id, student_id, user_id, semester_id, session_id, session_name, semester_number, amount_kobo,
                    status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'NOT_PAID', ?, ?) on conflict (tenant_id, student_id, semester_id) do nothing""",
                UUID.randomUUID(), TenantContext.require(), st.id(), st.userId(), sem.id(), sem.sessionId(), sem.sessionName(), sem.number(),
                s.feeKobo(), Timestamp.from(now), Timestamp.from(now));
        Fee fee = fees.findByStudentIdAndSemesterId(st.id(), sem.id()).orElseThrow();
        // A changed fee applies to fees nobody has started paying.
        if (fee.getStatus() == Fee.Status.NOT_PAID && fee.getAmountKobo() != s.feeKobo() && payments.findByFeeIdOrderByCreatedAtDesc(fee.getId()).isEmpty()) {
            fee.setAmountKobo(s.feeKobo());
            fee.setUpdatedAt(now);
            fees.save(fee);
        }
        return fee;
    }

    public Map<UUID, Refund> refundsOf(Collection<Payment> ps) {
        if (ps.isEmpty()) return Map.of();
        return refunds.findByPaymentIdIn(ps.stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.toMap(Refund::getPaymentId, Function.identity()));
    }

    public List<Payment> paymentsOf(Fee fee) {
        return payments.findByFeeIdOrderByCreatedAtDesc(fee.getId());
    }
}
