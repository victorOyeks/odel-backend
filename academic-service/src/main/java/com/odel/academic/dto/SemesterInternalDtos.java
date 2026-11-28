package com.odel.academic.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** GET /internal/academic/semesters: every semester with its session, for payments (B2) and registration. */
public final class SemesterInternalDtos {
    private SemesterInternalDtos() {}

    public record Window(LocalDate opensOn, LocalDate closesOn, LocalDate lateClosesOn) {}

    public record SemesterItem(UUID id, UUID sessionId, String sessionName, String sessionStatus, int number, String name, String label,
                               LocalDate startsOn, LocalDate endsOn, Window registration) {}

    public record Semesters(UUID currentSessionId, UUID currentSemesterId, List<SemesterItem> items) {}
}
