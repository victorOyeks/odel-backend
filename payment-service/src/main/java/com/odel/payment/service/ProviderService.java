package com.odel.payment.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.payment.client.GatewayException;
import com.odel.payment.client.PaymentGateway;
import com.odel.payment.client.PeerClient;
import com.odel.payment.config.PaymentProperties;
import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.dto.PeerDtos.TenantView;
import com.odel.payment.repository.FeeSettingsRepository;
import com.odel.payment.repository.FeeSettingsRepository.Settings;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The provider console: each university's fee and centre share, its settlement account behind the Paystack subaccount (the provider
 * enters it, the university only views it; owner's decision 28 Nov 2026) and the settlement report with the split.
 * Full account numbers live only in memory for 15 minutes between Verify and Save, and go only to Paystack.
 */
@Service
public class ProviderService {
    static final Duration RESOLUTION_TTL = Duration.ofMinutes(15);
    private final PeerClient peers;
    private final FeeService feeService;
    private final FeeSettingsRepository settings;
    private final PaymentGateway gateway;
    private final AuditService audit;
    private final ActorService actors;
    private final PaymentProperties props;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<UUID, Resolution> resolutions = new ConcurrentHashMap<>();

    record Resolution(UUID id, UUID by, String bankCode, String bankName, String accountNumber, String accountName, Instant at) {}

    public ProviderService(PeerClient peers, FeeService feeService, FeeSettingsRepository settings, PaymentGateway gateway, AuditService audit,
                           ActorService actors, PaymentProperties props, JdbcTemplate jdbc, Clock clock) {
        this.peers = peers;
        this.feeService = feeService;
        this.settings = settings;
        this.gateway = gateway;
        this.audit = audit;
        this.actors = actors;
        this.props = props;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private University item(TenantView t, Settings s) {
        ProviderAccount account = s.subaccountCode() == null ? null : new ProviderAccount(s.bankCode(), s.bankName(), StaffReportService.masked(s.accountLast4()),
                s.accountName(), s.subaccountCode(), s.accountSince(), s.accountUpdatedByName() == null ? null : new Person(s.accountUpdatedByName()));
        return new University(t.id(), t.name(), Money.naira(s.feeKobo()), Money.naira(s.centreShareKobo()),
                Money.naira(s.feeKobo() - s.centreShareKobo()), account, s.paymentsOpen());
    }

    private TenantView university(UUID tenantId) {
        TenantView t;
        try {
            t = peers.tenant(tenantId);
        } catch (ApiException e) {
            throw ApiException.notFound();
        }
        if (t == null || !"UNIVERSITY".equals(t.kind())) throw ApiException.notFound();
        return t;
    }

    public Items<University> universities() {
        return new Items<>(peers.universities().stream().map(t -> item(t, feeService.settings(t.id()))).toList());
    }

    public University one(UUID tenantId) {
        return item(university(tenantId), feeService.settings(tenantId));
    }

    public University amounts(CurrentUser cu, UUID tenantId, AmountsRequest in) {
        TenantView t = university(tenantId);
        Map<String, String> f = new LinkedHashMap<>();
        BigDecimal amount = in == null ? null : in.amount();
        BigDecimal share = in == null ? null : in.centreShare();
        if (amount == null || amount.compareTo(BigDecimal.valueOf(1000)) < 0 || amount.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            f.put("amount", "Enter a fee from ₦1,000 to ₦1,000,000.");
        }
        if (share == null || share.signum() < 0 || (amount != null && share.compareTo(amount) > 0)) {
            f.put("centreShare", "The centre's share must be between ₦0 and the fee.");
        }
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Check the highlighted fields.", f);
        Settings before = feeService.settings(tenantId);
        settings.saveAmounts(tenantId, Money.kobo(amount), Money.kobo(share), clock.instant());
        audit.record(new AuditService.Entry(tenantId, actors.actor(cu), "PAYMENT_SETTINGS_CHANGED", "UNIVERSITY", tenantId,
                actors.actor(cu).name() + " set " + t.name() + "'s platform fee to " + Money.label(Money.kobo(amount)) + " (centre share "
                        + Money.label(Money.kobo(share)) + ")",
                Map.of("amount", Money.naira(before.feeKobo()), "centreShare", Money.naira(before.centreShareKobo())),
                Map.of("amount", amount, "centreShare", share)));
        return one(tenantId);
    }

    public Items<BankView> banks() {
        try {
            return new Items<>(gateway.banks().stream().map(b -> new BankView(b.code(), b.name()))
                    .sorted(Comparator.comparing(BankView::name, String.CASE_INSENSITIVE_ORDER)).toList());
        } catch (GatewayException e) {
            throw gatewayError(e);
        }
    }

    static ApiException gatewayError(GatewayException e) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "gateway_error", e.getMessage() == null ? "Paystack could not be reached. Try again in a minute."
                : "Paystack: " + e.getMessage());
    }

    public Resolved resolve(CurrentUser cu, ResolveRequest in) {
        String number = in == null || in.accountNumber() == null ? "" : in.accountNumber().replaceAll("\\s", "");
        String code = in == null || in.bankCode() == null ? "" : in.bankCode().trim();
        Map<String, String> f = new LinkedHashMap<>();
        if (!number.matches("\\d{10}")) f.put("accountNumber", "Enter the 10-digit account number.");
        PaymentGateway.Bank bank;
        try {
            bank = gateway.banks().stream().filter(b -> b.code().equals(code)).findFirst().orElse(null);
        } catch (GatewayException e) {
            throw gatewayError(e);
        }
        if (bank == null) f.put("bankCode", "Choose the bank from the list.");
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Check the highlighted fields.", f);
        String name = gateway.resolveAccount(code, number);
        if (name == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "not_resolved", "The bank could not find that account. Check the number and the bank.");
        }
        Instant now = clock.instant();
        resolutions.values().removeIf(r -> r.at().plus(RESOLUTION_TTL).isBefore(now));
        Resolution r = new Resolution(UUID.randomUUID(), cu.userId(), code, bank.name(), number, name, now);
        resolutions.put(r.id(), r);
        return new Resolved(code, bank.name(), StaffReportService.masked(number.substring(6)), name, r.id());
    }

    public University saveAccount(CurrentUser cu, UUID tenantId, AccountRequest in) {
        TenantView t = university(tenantId);
        Resolution r = in == null || in.resolutionId() == null ? null : resolutions.get(in.resolutionId());
        if (r == null || !r.by().equals(cu.userId()) || r.at().plus(RESOLUTION_TTL).isBefore(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "resolve_first", "Verify the account number first.");
        }
        String confirmed = in.confirmedAccountName() == null ? "" : in.confirmedAccountName().trim().replaceAll("\\s+", " ");
        if (!confirmed.equalsIgnoreCase(r.accountName().trim().replaceAll("\\s+", " "))) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Confirm the account name the bank returned.",
                    Map.of("confirmedAccountName", "Confirm the account name the bank returned."));
        }
        Settings before = feeService.settings(tenantId);
        String code;
        try {
            double percentage = before.feeKobo() == 0 ? 0 : Math.round(10000.0 * (before.feeKobo() - before.centreShareKobo()) / before.feeKobo()) / 100.0;
            code = gateway.saveSubaccount(before.subaccountCode(), t.name() + " ODeL centre", r.bankCode(), r.accountNumber(), percentage);
        } catch (GatewayException e) {
            throw gatewayError(e);
        }
        var actor = actors.actor(cu);
        String last4 = r.accountNumber().substring(6);
        settings.saveAccount(tenantId, before.feeKobo(), before.centreShareKobo(), r.bankCode(), r.bankName(), last4, r.accountName(), code,
                cu.userId(), actor.name(), clock.instant());
        resolutions.remove(r.id());
        boolean change = before.subaccountCode() != null;
        Map<String, Object> oldV = change ? Map.of("bank", before.bankName() + " ••••" + before.accountLast4(), "accountName", before.accountName()) : null;
        audit.record(new AuditService.Entry(tenantId, actor, change ? "PAYMENT_ACCOUNT_CHANGED" : "PAYMENT_ACCOUNT_SET", "UNIVERSITY", tenantId,
                actor.name() + (change ? " changed " : " set ") + t.name() + "'s settlement account to " + r.bankName() + " ••••" + last4,
                oldV, Map.of("bank", r.bankName() + " ••••" + last4, "accountName", r.accountName())));
        return one(tenantId);
    }

    /** Dev seed: the account without a person (Paystack's test bank, or a fake code). */
    public void seedAccount(UUID tenantId, String universityName, String bankCode, String accountNumber) {
        Settings before = feeService.settings(tenantId);
        if (before.paymentsOpen()) return;
        String bankName = gateway.banks().stream().filter(b -> b.code().equals(bankCode)).map(PaymentGateway.Bank::name).findFirst().orElse("Test bank");
        String name = Optional.ofNullable(gateway.resolveAccount(bankCode, accountNumber)).orElse("TEST ACCOUNT");
        String code = gateway.saveSubaccount(null, universityName + " ODeL centre", bankCode, accountNumber, 80);
        String last4 = accountNumber.substring(Math.max(0, accountNumber.length() - 4));
        settings.saveAccount(tenantId, before.feeKobo(), before.centreShareKobo(), bankCode, bankName, last4, name, code, null, "Dev seed", clock.instant());
        audit.record(new AuditService.Entry(tenantId, AuditService.Actor.SYSTEM, "PAYMENT_ACCOUNT_SET", "UNIVERSITY", tenantId,
                "Dev seed set " + universityName + "'s settlement account to " + bankName + " ••••" + last4 + " (" + gateway.mode() + ")", null,
                Map.of("bank", bankName + " ••••" + last4)));
    }

    // ------------------------------------------------------------------ settlement

    record SRow(UUID tenantId, String reference, String matric, String label, Instant paidAt, String channel, String method, long amount,
                long provider, long centre, Long fee, String status, Long charged) {}

    public Settlement settlement(LocalDate from, LocalDate to, UUID tenantId, int page, int size) {
        LocalDate today = LocalDate.now(clock.withZone(Labels.LAGOS));
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null ? today : to;
        if (t.isBefore(f)) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The end date is before the start date.", Map.of("to", "Choose a date on or after the start."));
        Instant start = f.atStartOfDay(Labels.LAGOS).toInstant();
        Instant end = t.plusDays(1).atStartOfDay(Labels.LAGOS).toInstant();
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(start), Timestamp.from(end)));
        String extra = "";
        if (tenantId != null) { extra = " and tenant_id = ?"; args.add(tenantId); }
        List<SRow> rows = jdbc.query("""
                select tenant_id, reference, matric_number, fee_label, paid_at, channel, method, amount_kobo, provider_share_kobo, centre_share_kobo,
                       gateway_fee_kobo, status, charged_kobo
                from payments where paid_at >= ? and paid_at < ? and status in ('PAID', 'REFUNDING', 'REFUNDED', 'REVERSED')""" + extra
                        + " order by paid_at desc",
                (rs, i) -> new SRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
                        rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                        rs.getObject(11) == null ? null : rs.getLong(11), rs.getString(12), rs.getObject(13) == null ? null : rs.getLong(13)), args.toArray());
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, long[]> per = new LinkedHashMap<>();
        long[] tot = new long[7]; // payments, gross, provider, centre, gateway fees, refunded, provider net
        for (SRow r : rows) {
            names.computeIfAbsent(r.tenantId(), id -> { try { return peers.tenant(id).name(); } catch (Exception e) { return "Unknown university"; } });
            long fee = r.fee() == null ? 0 : r.fee();
            long refunded = "REFUNDING".equals(r.status()) || "REFUNDED".equals(r.status()) ? r.amount() : 0;
            long net = GatewayCharge.providerNet(r.provider(), r.amount(), r.charged(), r.fee());
            long[] u = per.computeIfAbsent(r.tenantId(), k -> new long[7]);
            for (long[] a : new long[][]{u, tot}) {
                a[0]++; a[1] += r.amount(); a[2] += r.provider(); a[3] += r.centre(); a[4] += fee; a[5] += refunded; a[6] += net;
            }
        }
        List<SettlementUniversity> unis = per.entrySet().stream().map(e -> {
            long[] a = e.getValue();
            return new SettlementUniversity(e.getKey(), names.get(e.getKey()), a[0], Money.naira(a[1]), Money.naira(a[2]), Money.naira(a[3]),
                    Money.naira(a[4]), Money.naira(a[6]), Money.naira(a[5]));
        }).sorted(Comparator.comparing(SettlementUniversity::name, String.CASE_INSENSITIVE_ORDER)).toList();
        int s = Math.max(1, Math.min(size, 500));
        int p = Math.max(page, 0);
        List<SettlementItem> items = rows.stream().skip((long) p * s).limit(s).map(r -> {
            long fee = r.fee() == null ? 0 : r.fee();
            return new SettlementItem(r.reference(), names.get(r.tenantId()), r.matric(), r.label(), r.paidAt(), methodShort(r.channel(), r.method()),
                    Money.naira(r.amount()), Money.naira(r.provider()), Money.naira(r.centre()), Money.naira(fee),
                    Money.naira(GatewayCharge.providerNet(r.provider(), r.amount(), r.charged(), r.fee())), r.status(),
                    Money.naira(GatewayCharge.studentCharge(r.amount(), r.charged())));
        }).toList();
        return new Settlement(f, t, new SettlementTotals(tot[0], Money.naira(tot[1]), Money.naira(tot[2]), Money.naira(tot[3]), Money.naira(tot[4]),
                Money.naira(tot[6]), Money.naira(tot[5])), unis, items, p, s, rows.size());
    }

    static String methodShort(String channel, String method) {
        if (channel == null) return "BANK_TRANSFER".equals(method) ? "Bank transfer" : "USSD".equals(method) ? "USSD" : "Card";
        return switch (channel) {
            case "card" -> "Card";
            case "bank_transfer", "dedicated_nuban" -> "Bank transfer";
            case "ussd" -> "USSD";
            default -> channel;
        };
    }
}
