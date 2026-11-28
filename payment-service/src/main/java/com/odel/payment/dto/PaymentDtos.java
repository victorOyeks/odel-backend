package com.odel.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Student, staff and provider views of fees and payments (docs/api-b2-payments.md). Amounts in naira. */
public final class PaymentDtos {
    private PaymentDtos() {}

    // ------------------------------------------------------------------ student

    public record MethodOption(String key, String label) {}

    public record UssdBank(String key, String name, String code) {}

    public record PaidRef(UUID id, String reference, Instant paidAt, String method, String methodLabel, String receiptUrl) {}

    public record RefundView(String reference, BigDecimal amount, String amountLabel, String status, Instant startedAt, Instant processedAt,
                             LocalDate expectedBy, String to, String reason) {}

    public record Duplicate(UUID paymentId, String reference, Instant paidAt, BigDecimal amount, String amountLabel, RefundView refund) {}

    public record Reversal(UUID paymentId, String reference, Instant reversedAt, String message) {}

    public record CheckoutView(String authorizationUrl) {}

    public record UssdView(String bank, String code, Instant expiresAt) {}

    public record TransferView(String bankName, String accountNumber, String accountName, BigDecimal amount, String amountLabel,
                               Instant expiresAt, String note) {}

    public record PaymentView(UUID id, UUID feeId, String label, String reference, String method, String methodLabel, BigDecimal amount,
                              String amountLabel, String status, String statusLabel, String message, Instant startedAt, Instant paidAt,
                              Instant expiresAt, CheckoutView checkout, UssdView ussd, TransferView transfer, String failureReason,
                              String receiptUrl, RefundView refund, boolean duplicate) {}

    public record FeeView(UUID id, UUID semesterId, UUID sessionId, String sessionName, int semesterNumber, String semesterName, String label,
                          BigDecimal amount, String amountLabel, String status, String statusLabel, String message, boolean locked,
                          PaymentView openPayment, PaidRef paidPayment, Duplicate duplicate, Reversal reversal, List<MethodOption> methods,
                          List<UssdBank> ussdBanks, String ussdOtherBanks, List<String> actions) {}

    public record Items<T>(List<T> items) {}

    public record StartRequest(UUID semesterId, String method, String ussdBank) {}

    // ------------------------------------------------------------------ staff (no split, ever)

    public record SemesterRef(UUID id, String label) {}

    public record Count(long count) {}

    public record PaidCard(long count, BigDecimal collected, String collectedLabel) {}

    public record ConfirmingCard(long count, Instant oldestStartedAt) {}

    public record RefundCard(long count, BigDecimal amount, String amountLabel) {}

    public record Summary(SemesterRef semester, BigDecimal amount, String amountLabel, PaidCard paid, ConfirmingCard confirming, Count notPaid,
                          RefundCard refunds, Count reversed, long students) {}

    public record CentreRef(String code, String name, String shortName) {}

    public record ProgrammeRef(String code, String name) {}

    public record Row(UUID studentId, String fullName, String matricNumber, CentreRef centre, ProgrammeRef programme, int level,
                      String status, String statusLabel, boolean paidTwice, String method, String methodLabel, String reference, Instant date,
                      UUID paymentId) {}

    public record StudentRef(UUID id, String fullName, String matricNumber, String email, ProgrammeRef programme, int level, String levelLabel,
                             CentreRef centre) {}

    public record StudentRecord(StudentRef student, FeeView fee, List<PaymentView> attempts) {}

    public record AccountView(String bankName, String accountNumberMasked, String accountName, Instant since) {}

    public record UniversitySettings(BigDecimal amount, String amountLabel, boolean paymentsOpen, AccountView settlementAccount) {}

    // ------------------------------------------------------------------ provider

    public record Person(String fullName) {}

    public record ProviderAccount(String bankCode, String bankName, String accountNumberMasked, String accountName, String subaccountCode,
                                  Instant since, Person updatedBy) {}

    public record University(UUID tenantId, String name, BigDecimal amount, BigDecimal centreShare, BigDecimal providerShare,
                             ProviderAccount settlementAccount, boolean paymentsOpen) {}

    public record AmountsRequest(BigDecimal amount, BigDecimal centreShare) {}

    public record BankView(String code, String name) {}

    public record ResolveRequest(String bankCode, String accountNumber) {}

    public record Resolved(String bankCode, String bankName, String accountNumberMasked, String accountName, UUID resolutionId) {}

    public record AccountRequest(UUID resolutionId, String confirmedAccountName) {}

    public record SettlementTotals(long payments, BigDecimal gross, BigDecimal provider, BigDecimal centre, BigDecimal gatewayFees,
                                   BigDecimal providerNet, BigDecimal refunded) {}

    public record SettlementUniversity(UUID tenantId, String name, long payments, BigDecimal gross, BigDecimal provider, BigDecimal centre,
                                       BigDecimal gatewayFees, BigDecimal providerNet, BigDecimal refunded) {}

    public record SettlementItem(String reference, String university, String matricNumber, String label, Instant paidAt, String method,
                                 BigDecimal amount, BigDecimal provider, BigDecimal centre, BigDecimal gatewayFee, BigDecimal providerNet,
                                 String status) {}

    public record Settlement(LocalDate from, LocalDate to, SettlementTotals totals, List<SettlementUniversity> universities,
                             List<SettlementItem> items, int page, int size, long total) {}

    // ------------------------------------------------------------------ internal

    public record FeeStatus(UUID studentId, UUID semesterId, String label, String status, boolean paid, Instant paidAt, String reference,
                            BigDecimal amount) {}

    public record FeeStatusesRequest(UUID semesterId, List<UUID> studentIds) {}

    public record InternalSummary(UUID semesterId, long paid, long confirming, long refunded, long reversed, List<UUID> paidStudentIds) {}

    // ------------------------------------------------------------------ dev

    public record SimulateRequest(String outcome, String channel, Boolean webhook) {}

    public record WebhookOut(String event, String body, String signature) {}

    public record Simulated(String reference, String outcome, List<WebhookOut> webhooks) {}

    public record RecheckRequest(Integer minutesAhead) {}

    public record RecheckResult(int checked, int paid, int failed, int expired) {}
}
