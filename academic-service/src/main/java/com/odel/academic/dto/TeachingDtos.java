package com.odel.academic.dto;

import java.util.List;
import java.util.UUID;

/** What academic-service answers content-service on /internal/academic/teaching (B3): the catalogue with who teaches each course. */
public final class TeachingDtos {
    private TeachingDtos() {}

    public record CourseRef(UUID id, String code, String title, int units, int level, int semester) {}

    public record TaughtCourse(CourseRef course, UUID lecturerId, List<UUID> facilitatorIds) {}

    public record SessionRef(UUID id, String name) {}

    public record Teaching(SessionRef session, List<TaughtCourse> items) {}
}
