package com.odel.registration.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The student's side (R-01 … R-09e), the slip and the registration views shared with staff screens. */
public final class RegistrationDtos {
    private RegistrationDtos() {}

    public record Ref(UUID id, String code, String name) {}

    public record StudentView(UUID id, String matricNumber, String fullName, Ref programme, int level, String levelLabel, Ref centre) {}

    public record SemesterView(UUID id, UUID sessionId, String sessionName, int number, String label, String name) {}

    public record WindowView(String state, LocalDate opensOn, LocalDate closesOn, LocalDate lateClosesOn, LocalDate changesCloseOn,
                             String label, LocalDate registerBy) {}

    public record FeeView(boolean required, String status, boolean paid, String label, String payPath) {}

    public record Blocked(String code, String message) {}

    public record Limits(int minUnits, int maxUnits, int maxElectives) {}

    public record Overview(StudentView student, SemesterView semester, WindowView window, FeeView fee, boolean canRegister, Blocked blocked,
                           RegistrationView registration, RequestDtos.RequestView lateRequest, RequestDtos.RequestView changeRequest, Limits limits) {}

    public record CourseOption(UUID courseId, String code, String title, int units, String kind, boolean locked, boolean selected, String fromSession) {}

    public record CourseChoice(SemesterView semester, WindowView window, Limits limits, List<CourseOption> compulsory, List<CourseOption> carryOvers,
                               List<CourseOption> electives, int lockedUnits, int lockedCourses, String subtitle) {}

    public record CheckRequest(UUID semesterId, List<UUID> electiveIds) {}

    public record Problem(String code, String message) {}

    public record CheckResult(int totalUnits, int courseCount, int electiveCount, boolean ok, List<Problem> problems, String counter) {}

    public record SubmitRequest(UUID semesterId, List<UUID> electiveIds, Boolean confirm) {}

    public record CourseLine(UUID courseId, String code, String title, int units, String kind, String kindLabel) {}

    public record HistoryItem(Instant at, String action, String summary, String by, int slipVersion) {}

    public record RegistrationView(UUID id, SemesterView semester, String status, boolean late, String slipNumber, int slipVersion,
                                   String verificationCode, List<CourseLine> courses, int courseCount, int totalUnits, Instant submittedAt,
                                   Instant updatedAt, LocalDate changesCloseOn, boolean canRequestChange, Ref centre, List<HistoryItem> history) {}

    public record Registrations(List<RegistrationView> items) {}

    public record Verification(boolean valid, String slipNumber, String studentName, String matricNumber, String programme, String semester,
                               int courseCount, int totalUnits, Instant submittedAt, Instant updatedAt, int slipVersion) {}
}
