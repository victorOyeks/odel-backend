package com.odel.content.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What other services answer content-service on /internal/**. */
public final class PeerDtos {
    private PeerDtos() {}

    // ---- academic-service
    public record Window(LocalDate opensOn, LocalDate closesOn, LocalDate lateClosesOn) {}

    public record SemesterItem(UUID id, UUID sessionId, String sessionName, String sessionStatus, int number, String name, String label,
                               LocalDate startsOn, LocalDate endsOn, Window registration) {}

    public record Semesters(UUID currentSessionId, UUID currentSemesterId, List<SemesterItem> items) {}

    public record CourseRef(UUID id, String code, String title, int units, int level, int semester) {}

    public record TaughtCourse(CourseRef course, UUID lecturerId, List<UUID> facilitatorIds) {}

    public record SessionRef(UUID id, String name) {}

    public record Teaching(SessionRef session, List<TaughtCourse> items) {}

    public record CentreCoordinator(UUID centreId, String centreCode, String centreName, UUID userId) {}

    public record Coordinators(SessionRef session, List<CentreCoordinator> items) {}

    // ---- registration-service
    public record RegCourse(UUID courseId, String code, String title, int units, String kind) {}

    public record StudentCourses(boolean registered, UUID semesterId, UUID registrationId, String slipNumber, List<RegCourse> courses, int totalUnits) {}

    public record CourseStudent(UUID studentId, UUID userId, String matricNumber, String fullName, UUID centreId, String centreCode, String centreName) {}

    public record CourseStudents(UUID semesterId, List<CourseStudent> items) {}

    // ---- student-service
    public record StudentRef(UUID id, UUID userId, String matricNumber, String fullName, String firstName, String email, UUID programmeId,
                             String programmeCode, String programmeName, int level, UUID centreId, String centreCode, String centreName,
                             String status) {}

    public record Roster(List<StudentRef> items) {}

    // ---- identity-service
    public record StaffView(UUID id, String fullName, String email) {}

    // ---- tenant-service
    public record TenantView(UUID id, String kind, String slug, String name, String shortName, String googleDomain, String status,
                             String primaryColor, String accentColor, String portalUrl, String logoUrl) {}

    // ---- notification-service
    public record TenantLook(String name, String shortName, String primaryColor) {}

    public record Message(UUID userId, String email, String templateKey, Map<String, ?> vars, String link, TenantLook tenant) {}

    public record BulkRequest(List<Message> messages) {}
}
