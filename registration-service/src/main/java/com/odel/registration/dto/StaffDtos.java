package com.odel.registration.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A-07 Registrations, A-07b one student, A-05 rules, carry-overs, dev fee overrides. */
public final class StaffDtos {
    private StaffDtos() {}

    public record Cards(long registered, Long paid, Long paidNotRegistered, long lateRequests, long lateRequestsAtCentres, long lateRequestsAtOffice,
                        long courseChanges) {}

    public record ReportRow(UUID studentId, String fullName, String matricNumber, RegistrationDtos.Ref programme, int level, RegistrationDtos.Ref centre,
                            Integer courseCount, Integer units, Instant submittedAt, Instant changedAt, UUID registrationId, String status,
                            String statusLabel, String feeStatus) {}

    public record Report(RegistrationDtos.SemesterView semester, RegistrationDtos.WindowView window, String subtitle, Cards cards, List<ReportRow> items,
                         int page, int size, long total, boolean feeStatusKnown) {}

    public record StudentRegistration(RegistrationDtos.StudentView student, RegistrationDtos.RegistrationView registration,
                                      List<RequestDtos.RequestView> requests, List<CarryOverView> carryOvers) {}

    // ---- rules (A-05) ----
    public record FeeRules(Boolean blockRegistration, Boolean blockExams, Boolean previewsWhenUnpaid) {}

    public record LevelRule(int level, Integer minUnits, Integer maxUnits, Integer maxElectives, Integer compulsoryUnits, Boolean fits) {}

    public record RulesWindow(LocalDate opensOn, LocalDate closesOn, LocalDate lateClosesOn, LocalDate changesCloseOn, boolean changesCloseOnDefault) {}

    public record ProgrammeBrief(UUID id, String name) {}

    public record Rules(RegistrationDtos.SemesterView semester, RulesWindow window, FeeRules fee, ProgrammeBrief programme, boolean inheritedFromDefault,
                       List<LevelRule> levels, List<ProgrammeBrief> programmesWithOwnLimits, String note) {}

    public record RulesUpdate(UUID semesterId, LocalDate changesCloseOn, FeeRules fee, UUID programmeId, List<LevelRule> levels) {}

    // ---- carry-overs ----
    public record CarryOverView(UUID id, UUID studentId, UUID courseId, String code, String title, int units, int semester, String fromSession,
                                String source, String status, Instant createdAt, String createdBy) {}

    public record CarryOvers(List<CarryOverView> items) {}

    public record CarryOverIn(UUID studentId, String courseCode, String fromSession) {}

    public record ImportIn(String key, String fileName) {}

    public record ImportProblem(int row, String message) {}

    public record ImportResult(int totalRows, int added, int skipped, List<ImportProblem> problems) {}

    // ---- dev ----
    public record FeeOverride(UUID studentId, UUID semesterId, String status) {}
}
