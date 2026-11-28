package com.odel.academic.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What academic-service answers registration-service on /internal/academic/** (B2). */
public final class RegistrationSupportDtos {
    private RegistrationSupportDtos() {}

    public record SemesterInfo(UUID id, UUID sessionId, String sessionName, String sessionStatus, int number, String name,
                               LocalDate startsOn, LocalDate endsOn, LocalDate regOpensOn, LocalDate regClosesOn, LocalDate lateRegClosesOn) {}

    public record CalendarSession(UUID id, String name, String status, List<SemesterInfo> semesters) {}

    public record Calendar(CalendarSession currentSession) {}

    public record CourseInfo(UUID id, String code, String title, int units, int level, int semester, String kind) {}

    public record Courses(List<CourseInfo> items) {}

    public record SessionRef(UUID id, String name) {}

    public record CentreCoordinator(UUID centreId, String centreCode, String centreName, UUID userId) {}

    public record Coordinators(SessionRef session, List<CentreCoordinator> items) {}
}
