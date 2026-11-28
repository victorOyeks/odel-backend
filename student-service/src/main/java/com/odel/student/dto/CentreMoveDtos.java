package com.odel.student.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A-06 Move to another centre, and the calls it makes to academic-service and registration-service. */
public final class CentreMoveDtos {
    private CentreMoveDtos() {}

    public record MoveRequest(String centreCode, String reason, LocalDate effectiveOn) {}

    public record CentreRef(UUID id, String code, String name) {}

    public record Notified(String name, String role, String email) {}

    public record Move(UUID id, CentreRef from, CentreRef to, String reason, LocalDate effectiveOn, String status, String requestedBy,
                       Instant createdAt, Instant appliedAt, Instant cancelledAt, List<Notified> notified, List<String> notNotified,
                       Integer requestsMoved, String summary) {}

    public record MoveResult(Move move, StudentDtos.Record record) {}

    public record Moves(List<Move> items) {}

    // ---- academic-service GET /internal/academic/coordinators ----
    public record SessionRef(UUID id, String name) {}

    public record CentreCoordinator(UUID centreId, String centreCode, String centreName, UUID userId) {}

    public record Coordinators(SessionRef session, List<CentreCoordinator> items) {}

    // ---- registration-service POST /internal/registration/students/{id}/centre-moved ----
    public record CentreMoved(UUID centreId, String centreCode, String centreName) {}

    public record CentreMovedResult(int requestsMoved, int registrationsMoved) {}
}
