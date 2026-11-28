package com.odel.registration.dto;

import java.util.List;
import java.util.UUID;

/** What registration-service answers other services on /internal/registration/**. */
public final class InternalDtos {
    private InternalDtos() {}

    public record Course(UUID courseId, String code, String title, int units, String kind) {}

    public record StudentCourses(boolean registered, UUID semesterId, UUID registrationId, String slipNumber, List<Course> courses, int totalUnits) {}

    public record StudentSummary(UUID semesterId, String semesterLabel, String state, boolean registered, int courseCount, int totalUnits,
                                 String slipNumber, long pendingRequests) {}

    public record CentreCount(UUID centreId, long registered) {}

    public record Summary(UUID semesterId, long registered, long lateRequestsPending, long changeRequestsPending, List<CentreCount> byCentre) {}

    /** A student registered for a course in a semester (content-service's class list, B3). */
    public record CourseStudent(UUID studentId, UUID userId, String matricNumber, String fullName, UUID centreId, String centreCode, String centreName) {}

    public record CourseStudents(UUID semesterId, List<CourseStudent> items) {}

    public record CentreMoved(UUID centreId, String centreCode, String centreName) {}

    public record CentreMovedResult(int requestsMoved, int registrationsMoved) {}
}
