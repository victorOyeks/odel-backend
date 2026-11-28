package com.odel.registration.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What other services answer registration-service on /internal/**. */
public final class PeerDtos {
    private PeerDtos() {}

    // ---- academic-service ----
    public record SemesterInfo(UUID id, UUID sessionId, String sessionName, String sessionStatus, int number, String name,
                               LocalDate startsOn, LocalDate endsOn, LocalDate regOpensOn, LocalDate regClosesOn, LocalDate lateRegClosesOn) {}

    public record CalendarSession(UUID id, String name, String status, List<SemesterInfo> semesters) {}

    public record Calendar(CalendarSession currentSession) {}

    public record CourseInfo(UUID id, String code, String title, int units, int level, int semester, String kind) {}

    public record Courses(List<CourseInfo> items) {}

    public record SessionRef(UUID id, String name) {}

    public record CentreCoordinator(UUID centreId, String centreCode, String centreName, UUID userId) {}

    public record Coordinators(SessionRef session, List<CentreCoordinator> items) {}

    public record ProgrammeRef(UUID id, String code, String name, List<Integer> levels, UUID departmentId, String departmentName, UUID facultyId) {}

    public record CentreRef(UUID id, String code, String name, String phone) {}

    public record Reference(Object currentSession, List<Object> sessions, List<ProgrammeRef> programmes, List<CentreRef> centres) {}

    // ---- student-service ----
    public record StudentRef(UUID id, UUID userId, String matricNumber, String fullName, String firstName, String email, UUID programmeId,
                             String programmeCode, String programmeName, int level, UUID centreId, String centreCode, String centreName,
                             String status) {}

    public record Roster(List<StudentRef> items) {}

    // ---- identity-service ----
    public record StaffView(UUID id, String fullName, String email) {}

    // ---- tenant-service ----
    public record TenantView(UUID id, String kind, String slug, String name, String shortName, String googleDomain, String status,
                             String primaryColor, String accentColor, String portalUrl, String logoUrl) {}

    // ---- notification-service ----
    public record TenantLook(String name, String shortName, String primaryColor) {}

    public record Message(UUID userId, String email, String templateKey, Map<String, ?> vars, String link, TenantLook tenant) {}

    // ---- payment-service ----
    public record FeeStatus(UUID studentId, UUID semesterId, String status, Boolean paid, String reference, Instant paidAt) {}

    public record FeeBulkRequest(UUID semesterId, List<UUID> studentIds) {}

    public record FeeBulk(List<FeeStatus> items) {}
}
