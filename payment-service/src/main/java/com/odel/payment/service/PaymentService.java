package com.odel.payment.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import com.odel.payment.client.GatewayException;
import com.odel.payment.client.PaymentGateway;
import com.odel.payment.client.PaymentGateway.Verification;
import com.odel.payment.client.PeerClient;
import com.odel.payment.config.PaymentProperties;
import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.dto.PeerDtos.Semester;
import com.odel.payment.dto.PeerDtos.Semesters;
import com.odel.payment.dto.PeerDtos.Student;
import com.odel.payment.dto.PeerDtos.TenantView;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.entity.Refund;
import com.odel.payment.repository.FeeRepository;
import com.odel.payment.repository.FeeSettingsRepository.Settings;
import com.odel.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** The student's side: fee cards (P-01…), starting a payment (P-02…P-04b), checking it (P-05…P-09), payments list (P-11), receipts. */
@Service
public class PaymentService {
    static final Duration OPEN_FOR = Duration.ofMinutes(30);
    private final FeeService feeService;
    private final FeeRepository fees;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PeerClient peers;
    private final ReceiptService receipts;
    private final AuditService audit;
    private final ActorService actors;
    private final PaymentProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(FeeService feeService, FeeRepository fees, PaymentRepository payments, PaymentGateway gateway, PeerClient peers,
                          ReceiptService receipts, AuditService audit, ActorService actors, PaymentProperties props, TransactionTemplate tx, Clock clock) {
        this.feeService = feeService;
        this.fees = fees;
        this.payments = payments;
        this.gateway = gateway;
        this.peers = peers;
        this.receipts = receipts;
        this.audit = audit;
        this.actors = actors;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    private Student me(CurrentUser cu) {
        return peers.studentByUser(cu.userId());
    }

    private FeeView view(Fee fee) {
        List<Payment> ps = feeService.paymentsOf(fee);
        return Views.fee(fee, ps, feeService.refundsOf(ps));
    }

    public FeeView current(CurrentUser cu) {
        Student st = me(cu);
        Semester sem = FeeService.pick(peers.semesters(), null);
        return tx.execute(t -> view(feeService.ensure(st, sem)));
    }

    /** Every semester of the current session, and older fees, newest semester first. */
    public Items<FeeView> all(CurrentUser cu) {
        Student st = me(cu);
        Semesters sems = peers.semesters();
        return tx.execute(t -> {
            Map<UUID, Fee> byId = new LinkedHashMap<>();
            if (sems != null && sems.currentSessionId() != null) {
                sems.items().stream().filter(s -> s.sessionId().equals(sems.currentSessionId()))
                        .forEach(s -> { Fee f = feeService.ensure(st, s); byId.put(f.getId(), f); });
            }
            for (Fee f : fees.findByStudentIdOrderByCreatedAtDesc(st.id())) byId.putIfAbsent(f.getId(), f);
            List<Fee> list = new ArrayList<>(byId.values());
            list.sort(Comparator.comparing(Fee::getSessionName).thenComparing(Fee::getSemesterNumber).reversed());
            return new Items<>(list.stream().map(this::view).toList());
        });
    }

    public record Started(PaymentView payment, boolean created) {}

    public Started start(CurrentUser cu, StartRequest in) {
        Payment.Method method;
        try {
            method = Payment.Method.valueOf(in == null || in.method() == null ? "" : in.method().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Choose how to pay: card, bank transfer or USSD.",
                    Map.of("method", "Choose card, bank transfer or USSD."));
        }
        var bank = method == Payment.Method.USSD ? UssdBanks.find(in.ussdBank()) : null;
        if (method == Payment.Method.USSD && bank == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ussd_bank_not_supported",
                    "USSD works with GTBank, UBA, Sterling and Zenith. Pay by card or bank transfer instead.", Map.of("ussdBank",
                    "Choose GTBank, UBA, Sterling or Zenith, or pay by card or bank transfer."), null);
        }
        Student st = me(cu);
        Semester sem = FeeService.pick(peers.semesters(), in.semesterId());
        UUID tenantId = TenantContext.require();
        Settings settings = feeService.settings(tenantId);
        if (!settings.paymentsOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "payments_not_ready",
                    "Payments are not open for your university yet. Try again later or call the ODeL office.");
        }
        TenantView tenant = peers.tenant();
        String ussdKey = bank == null ? null : bank.key();
        try {
            return tx.execute(t -> {
                Fee fee = fees.lockInTenant(feeService.ensure(st, sem).getId()).orElseThrow();
                if (fee.getStatus() == Fee.Status.PAID) {
                    throw new ApiException(HttpStatus.CONFLICT, "already_paid",
                            "Your fee for " + fee.label() + " is paid. Your receipt is under Payments.");
                }
                Instant now = clock.instant();
                Payment open = payments.findOpen(fee.getId()).orElse(null);
                if (open != null) {
                    if (open.getStatus() == Payment.Status.CONFIRMING) {
                        throw new ApiException(HttpStatus.CONFLICT, "payment_confirming",
                                "We are waiting for your bank to confirm your last payment. Do not pay again.");
                    }
                    boolean same = open.getMethod() == method && Objects.equals(open.getUssdBank(), ussdKey);
                    if (same && (open.getExpiresAt() == null || open.getExpiresAt().isAfter(now.plusSeconds(60)))) {
                        return new Started(Views.payment(open, null), false);
                    }
                    open.setStatus(Payment.Status.CANCELLED);
                    open.setUpdatedAt(now);
                    payments.saveAndFlush(open);
                }
                Payment p = newPayment(fee, st, method, ussdKey, settings, tenant, now);
                PaymentGateway.Charge charge = new PaymentGateway.Charge(p.getEmail(), p.getAmountKobo(), p.getReference(),
                        settings.subaccountCode(), p.getProviderShareKobo(),
                        Map.of("fee", fee.label(), "matric_number", st.matricNumber(), "student", st.fullName(), "university", tenant.name(),
                                "custom_fields", List.of(Map.of("display_name", "Fee", "variable_name", "fee", "value", "Platform fee " + fee.label()),
                                        Map.of("display_name", "Matric number", "variable_name", "matric_number", "value", st.matricNumber()))));
                switch (method) {
                    case CARD -> {
                        String portal = tenant.portalUrl() == null ? "" : tenant.portalUrl();
                        PaymentGateway.Checkout c = gateway.initialize(charge, portal + "/payments/return");
                        p.setAuthorizationUrl(c.authorizationUrl());
                        p.setAccessCode(c.accessCode());
                    }
                    case USSD -> {
                        PaymentGateway.UssdCode u = gateway.chargeUssd(charge, UssdBanks.type(bank));
                        p.setUssdCode(u.code());
                        p.setExpiresAt(now.plus(OPEN_FOR));
                    }
                    case BANK_TRANSFER -> {
                        PaymentGateway.TransferAccount a = gateway.chargeTransfer(charge, now.plus(OPEN_FOR));
                        p.setTransferBank(a.bankName());
                        p.setTransferAccountNumber(a.accountNumber());
                        p.setTransferAccountName(a.accountName());
                        p.setChargedKobo(a.amountKobo());
                        p.setExpiresAt(a.expiresAt());
                    }
                }
                payments.save(p);
                audit.record(new AuditService.Entry(tenantId, actors.student(cu, st.fullName()), "PAYMENT_STARTED", "PAYMENT", p.getId(),
                        st.fullName() + " started paying the platform fee for " + fee.label() + " by " + Labels.requestedMethod(method).toLowerCase(Locale.ROOT)
                                + " (" + p.getReference() + ")", null, Map.of("reference", p.getReference(), "method", method.name())));
                return new Started(Views.payment(p, null), true);
            });
        } catch (GatewayException e) {
            if (e.refused()) {
                // Paystack answered and said no: name its reason in plain words and point to the other ways to pay.
                String way = switch (method) {
                    case CARD -> "card payment";
                    case USSD -> "USSD payment";
                    case BANK_TRANSFER -> "bank transfer";
                };
                String instead = switch (method) {
                    case CARD -> "Pay by bank transfer or USSD instead";
                    case USSD -> "Pay by card or bank transfer instead";
                    case BANK_TRANSFER -> "Pay by card or USSD instead";
                };
                String why = e.getMessage() == null || e.getMessage().isBlank() ? "" : " (" + e.getMessage().replaceAll("[.\\s]+$", "") + ")";
                throw new ApiException(HttpStatus.BAD_GATEWAY, "gateway_error",
                        "Paystack could not start this " + way + why + ". Nothing was charged. " + instead + ", or try again later.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "gateway_error",
                    "We could not reach the payment provider. Nothing was charged. Try again in a minute.");
        }
    }

    private Payment newPayment(Fee fee, Student st, Payment.Method method, String ussdKey, Settings s, TenantView tenant, Instant now) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setFeeId(fee.getId());
        p.setStudentId(st.id());
        p.setUserId(st.userId());
        p.setStudentName(st.fullName());
        p.setMatricNumber(st.matricNumber());
        p.setFeeLabel(fee.label());
        p.setReference(uniqueReference(tenant.name()));
        p.setMethod(method);
        p.setStatus(Payment.Status.PENDING);
        p.setAmountKobo(fee.getAmountKobo());
        long centre = Math.min(s.centreShareKobo(), fee.getAmountKobo());
        p.setCentreShareKobo(centre);
        p.setProviderShareKobo(Money.providerShare(fee.getAmountKobo(), centre));
        p.setSubaccountCode(s.subaccountCode());
        p.setEmail(payerEmail(st.email(), st.id(), props.fallbackEmailDomain()));
        p.setUssdBank(ussdKey);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return p;
    }

    private String uniqueReference(String universityName) {
        for (int i = 0; i < 20; i++) {
            String r = References.payment(universityName);
            if (payments.countByReferenceAnywhere(r) == 0) return r;
        }
        throw new IllegalStateException("No free payment reference");
    }

    /** The student's own payment, or 404. */
    private Payment mine(CurrentUser cu, UUID id) {
        Payment p = payments.findInTenant(id).orElseThrow(ApiException::notFound);
        if (!cu.userId().equals(p.getUserId())) throw ApiException.notFound();
        return p;
    }

    private PaymentView view(Payment p, String message) {
        Map<UUID, Refund> r = feeService.refundsOf(List.of(p));
        PaymentView v = Views.payment(p, r.get(p.getId()));
        if (message == null) return v;
        return new PaymentView(v.id(), v.feeId(), v.label(), v.reference(), v.method(), v.methodLabel(), v.amount(), v.amountLabel(), v.status(),
                v.statusLabel(), message, v.startedAt(), v.paidAt(), v.expiresAt(), v.checkout(), v.ussd(), v.transfer(), v.failureReason(),
                v.receiptUrl(), v.refund(), v.duplicate(), v.charge());
    }

    public PaymentView get(CurrentUser cu, UUID id) {
        return tx.execute(t -> view(mine(cu, id), null));
    }

    public Items<PaymentView> mine(CurrentUser cu) {
        Student st = me(cu);
        return tx.execute(t -> {
            List<Payment> ps = payments.findByStudentIdOrderByCreatedAtDesc(st.id());
            Map<UUID, Refund> r = feeService.refundsOf(ps);
            return new Items<>(ps.stream().map(p -> Views.payment(p, r.get(p.getId()))).toList());
        });
    }

    /** P-04 "I have made the transfer", P-04b "I have dialled the code". */
    public PaymentView sent(CurrentUser cu, UUID id) {
        return tx.execute(t -> {
            Payment p = mine(cu, id);
            FeeLedger.claimed(p, clock.instant());
            payments.save(p);
            return view(p, null);
        });
    }

    public PaymentView cancel(CurrentUser cu, UUID id) {
        return tx.execute(t -> {
            Payment p = mine(cu, id);
            if (p.getStatus() == Payment.Status.PENDING) {
                p.setStatus(Payment.Status.CANCELLED);
                p.setUpdatedAt(clock.instant());
                payments.save(p);
            } else if (p.getStatus() == Payment.Status.CONFIRMING) {
                throw new ApiException(HttpStatus.CONFLICT, "payment_confirming",
                        "We are waiting for your bank to confirm this payment. It cannot be cancelled now.");
            }
            return view(p, null);
        });
    }

    /** The browser back from checkout, or "Check payment status": Paystack's answer for the screen. Never marks a fee paid. */
    public PaymentView check(CurrentUser cu, UUID id) {
        Payment p0 = tx.execute(t -> mine(cu, id));
        if (Payment.MONEY_RECEIVED.contains(p0.getStatus())) return tx.execute(t -> view(p0, null));
        Verification v;
        try {
            v = gateway.verify(p0.getReference());
        } catch (GatewayException e) {
            return tx.execute(t -> view(p0, "We could not reach Paystack to check. Try again in a minute. Do not pay again."));
        }
        return tx.execute(t -> {
            Payment p = mine(cu, id);
            String message = FeeLedger.checked(p, v, clock.instant());
            payments.save(p);
            return view(p, message);
        });
    }

    public PaymentView returned(CurrentUser cu, String reference) {
        Payment p = tx.execute(t -> payments.findByReference(reference == null ? "" : reference.trim()).orElseThrow(ApiException::notFound));
        return check(cu, p.getId());
    }

    public record Pdf(String fileName, byte[] bytes) {}

    /** The receipt: the student's own payment, or anyone the caller allows (staff). Only for money that arrived and was kept. */
    public Pdf receipt(CurrentUser cu, UUID id, boolean staff) {
        Payment p = tx.execute(t -> staff ? payments.findInTenant(id).orElseThrow(ApiException::notFound) : mine(cu, id));
        if (p.getPaidAt() == null || p.isDuplicate() || !Payment.MONEY_RECEIVED.contains(p.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_paid", "There is no receipt for this payment: it was not paid.");
        }
        Fee fee = tx.execute(t -> fees.findInTenant(p.getFeeId()).orElseThrow());
        Student st;
        try {
            st = peers.student(p.getStudentId());
        } catch (ApiException e) {
            st = null;
        }
        return new Pdf(ReceiptService.fileName(p), receipts.pdf(p, fee, st));
    }

    private static final java.util.Set<String> RESERVED_TLDS = java.util.Set.of("test", "invalid", "example", "localhost", "local");

    /**
     * Paystack needs a valid email on every transaction. A student with no email, or one on a reserved test domain (.test,
     * .invalid, example.com…), pays as student-<id>@<fallback domain>; Paystack refuses the reserved ones outright.
     */
    static String payerEmail(String email, UUID studentId, String fallbackDomain) {
        if (email != null && !email.isBlank() && email.contains("@")) {
            String host = email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(java.util.Locale.ROOT);
            String tld = host.substring(host.lastIndexOf('.') + 1);
            boolean reserved = RESERVED_TLDS.contains(tld) || host.equals("example.com") || host.equals("example.org") || host.equals("example.net")
                    || host.endsWith(".example.com");
            if (!reserved) return email.trim();
        }
        return "student-" + studentId.toString().substring(0, 8) + "@" + fallbackDomain;
    }
}
