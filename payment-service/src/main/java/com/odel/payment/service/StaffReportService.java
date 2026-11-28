package com.odel.payment.service;

import com.odel.common.PageResponse;
import com.odel.common.tenancy.TenantContext;
import com.odel.payment.client.PeerClient;
import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.dto.PeerDtos.Semester;
import com.odel.payment.dto.PeerDtos.Student;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.entity.Refund;
import com.odel.payment.repository.FeeRepository;
import com.odel.payment.repository.FeeSettingsRepository.Settings;
import com.odel.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The university's payment views (A-01 cards and list, export, A-02 one student's record). Never the split. */
@Service
public class StaffReportService {
    private final PeerClient peers;
    private final FeeService feeService;
    private final FeeRepository fees;
    private final PaymentRepository payments;
    private final TransactionTemplate tx;

    public StaffReportService(PeerClient peers, FeeService feeService, FeeRepository fees, PaymentRepository payments, TransactionTemplate tx) {
        this.peers = peers;
        this.feeService = feeService;
        this.fees = fees;
        this.payments = payments;
        this.tx = tx;
    }

    /** One student's line for the semester, with every attempt. */
    record Line(Student student, Fee fee, List<Payment> attempts, Map<UUID, Refund> refunds) {
        Payment open() { return attempts.stream().filter(p -> p.getStatus().open()).findFirst().orElse(null); }

        String status() {
            if (fee == null) return "NOT_PAID";
            String s = FeeLedger.effectiveStatus(fee, open());
            if ("NOT_PAID".equals(s) && !attempts.isEmpty() && attempts.get(0).getStatus() == Payment.Status.FAILED) return "FAILED";
            return s;
        }

        boolean paidTwice() { return attempts.stream().anyMatch(Payment::isDuplicate); }

        Payment shown() {
            if (fee != null && fee.getPaidPaymentId() != null && "PAID".equals(status())) {
                return attempts.stream().filter(p -> p.getId().equals(fee.getPaidPaymentId())).findFirst().orElse(null);
            }
            Payment o = open();
            if (o != null) return o;
            if (fee != null && fee.getStatus() == Fee.Status.REVERSED) {
                return attempts.stream().filter(p -> p.getStatus() == Payment.Status.REVERSED).findFirst().orElse(attempts.get(0));
            }
            return attempts.isEmpty() ? null : attempts.get(0);
        }
    }

    private List<Line> lines(Semester sem) {
        List<Student> students = peers.students();
        return tx.execute(t -> {
            Map<UUID, Fee> bySt = fees.findBySemesterId(sem.id()).stream().collect(Collectors.toMap(Fee::getStudentId, Function.identity()));
            List<Payment> all = bySt.isEmpty() ? List.of() : payments.findByFeeIds(bySt.values().stream().map(Fee::getId).toList());
            Map<UUID, List<Payment>> byFee = all.stream().collect(Collectors.groupingBy(Payment::getFeeId, LinkedHashMap::new, Collectors.toList()));
            Map<UUID, Refund> refunds = feeService.refundsOf(all);
            List<Line> out = new ArrayList<>(students.size());
            for (Student s : students) {
                Fee f = bySt.get(s.id());
                out.add(new Line(s, f, f == null ? List.of() : byFee.getOrDefault(f.getId(), List.of()), refunds));
            }
            return out;
        });
    }

    private Semester semester(UUID semesterId) {
        return FeeService.pick(peers.semesters(), semesterId);
    }

    public Summary summary(UUID semesterId) {
        Semester sem = semester(semesterId);
        List<Line> lines = lines(sem);
        Settings s = feeService.settings(TenantContext.require());
        long paid = 0, collected = 0, confirming = 0, reversed = 0, refundCount = 0, refundKobo = 0;
        Instant oldest = null;
        for (Line l : lines) {
            String st = l.status();
            if ("PAID".equals(st)) { paid++; collected += l.fee().getAmountKobo(); }
            if ("CONFIRMING".equals(st)) {
                confirming++;
                Instant at = l.open().getCreatedAt();
                if (oldest == null || at.isBefore(oldest)) oldest = at;
            }
            if ("REVERSED".equals(st)) reversed++;
            for (Payment p : l.attempts()) {
                if (p.isDuplicate() || p.getStatus() == Payment.Status.REFUNDING || p.getStatus() == Payment.Status.REFUNDED) {
                    refundCount++;
                    refundKobo += p.getAmountKobo();
                }
            }
        }
        long notPaid = lines.size() - paid - confirming;
        return new Summary(new SemesterRef(sem.id(), sem.label()), Money.naira(s.feeKobo()), Money.label(s.feeKobo()) + " per student",
                new PaidCard(paid, Money.naira(collected), Money.label(collected) + " collected"), new ConfirmingCard(confirming, oldest),
                new Count(notPaid), new RefundCard(refundCount, Money.naira(refundKobo), Money.label(refundKobo)), new Count(reversed), lines.size());
    }

    public record Filter(UUID semesterId, String q, String centre, String status, String method, LocalDate from, LocalDate to) {}

    private List<Row> rows(Filter f) {
        Semester sem = semester(f.semesterId());
        String q = f.q() == null ? "" : f.q().trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (Line l : lines(sem)) {
            Student s = l.student();
            Payment p = l.shown();
            String status = l.status();
            if (f.status() != null && !f.status().isBlank()) {
                String want = f.status().trim().toUpperCase(Locale.ROOT);
                if (want.equals("PAID_TWICE") ? !l.paidTwice() : !want.equals(status)) continue;
            }
            if (f.centre() != null && !f.centre().isBlank()) {
                String c = f.centre().trim().toLowerCase(Locale.ROOT);
                if (s.centre() == null || !(c.equalsIgnoreCase(String.valueOf(s.centre().code())) || c.equalsIgnoreCase(String.valueOf(s.centre().id())))) continue;
            }
            if (f.method() != null && !f.method().isBlank() && (p == null || !Labels.methodKey(p).equalsIgnoreCase(f.method().trim()))) continue;
            Instant date = p == null ? null : (p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt());
            if (f.from() != null && (date == null || LocalDate.ofInstant(date, Labels.LAGOS).isBefore(f.from()))) continue;
            if (f.to() != null && (date == null || LocalDate.ofInstant(date, Labels.LAGOS).isAfter(f.to()))) continue;
            if (!q.isEmpty()) {
                boolean hit = s.fullName().toLowerCase(Locale.ROOT).contains(q) || s.matricNumber().toLowerCase(Locale.ROOT).contains(q)
                        || l.attempts().stream().anyMatch(a -> a.getReference().toLowerCase(Locale.ROOT).contains(q));
                if (!hit) continue;
            }
            rows.add(new Row(s.id(), s.fullName(), s.matricNumber(),
                    s.centre() == null ? null : new CentreRef(s.centre().code(), s.centre().name(), s.centre().shortName()),
                    s.programme() == null ? null : new ProgrammeRef(s.programme().code(), s.programme().name()), s.level(),
                    status, Labels.feeStatus(status), l.paidTwice(),
                    p == null ? null : Labels.methodKey(p), p == null ? null : Labels.methodShort(p), p == null ? null : p.getReference(),
                    date, p == null ? null : p.getId()));
        }
        rows.sort(Comparator.comparing((Row r) -> r.date() == null ? Instant.MIN : r.date()).reversed()
                .thenComparing(Row::matricNumber));
        return rows;
    }

    public PageResponse<Row> list(Filter f, int page, int size) {
        List<Row> all = rows(f);
        int s = Math.max(1, Math.min(size, 200));
        int from = Math.min(Math.max(page, 0) * s, all.size());
        return new PageResponse<>(all.subList(from, Math.min(from + s, all.size())), Math.max(page, 0), s, all.size());
    }

    public String csv(Filter f) {
        StringBuilder sb = new StringBuilder("student,matric_number,centre,programme,level,status,method,reference,date\n");
        for (Row r : rows(f)) {
            sb.append(String.join(",", cell(r.fullName()), cell(r.matricNumber()), cell(r.centre() == null ? "" : r.centre().name()),
                    cell(r.programme() == null ? "" : r.programme().name()), r.level() + "L", cell(r.statusLabel()),
                    cell(r.methodLabel() == null ? "" : r.methodLabel()), cell(r.reference() == null ? "" : r.reference()),
                    r.date() == null ? "" : Labels.dayTime(r.date()))).append('\n');
        }
        return sb.toString();
    }

    static String cell(String v) {
        if (v == null) return "";
        String s = v;
        // Spreadsheet formula injection: a cell starting with = + - @ is shown as text.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    public StudentRecord student(UUID studentId, UUID semesterId) {
        Student s = peers.student(studentId);
        Semester sem = semester(semesterId);
        return tx.execute(t -> {
            Fee fee = fees.findByStudentIdAndSemesterId(s.id(), sem.id()).orElseGet(() -> feeService.ensure(s, sem));
            List<Payment> ps = feeService.paymentsOf(fee);
            Map<UUID, Refund> r = feeService.refundsOf(ps);
            return new StudentRecord(new StudentRef(s.id(), s.fullName(), s.matricNumber(), s.email(),
                    s.programme() == null ? null : new ProgrammeRef(s.programme().code(), s.programme().name()), s.level(), s.levelLabel(),
                    s.centre() == null ? null : new CentreRef(s.centre().code(), s.centre().name(), s.centre().shortName())),
                    Views.fee(fee, ps, r), ps.stream().map(p -> Views.payment(p, r.get(p.getId()))).toList());
        });
    }

    public UniversitySettings settings() {
        Settings s = feeService.settings(TenantContext.require());
        return new UniversitySettings(Money.naira(s.feeKobo()), Money.label(s.feeKobo()), s.paymentsOpen(),
                s.subaccountCode() == null ? null : new AccountView(s.bankName(), masked(s.accountLast4()), s.accountName(), s.accountSince()));
    }

    public static String masked(String last4) {
        return last4 == null ? null : "••••" + last4;
    }

}
