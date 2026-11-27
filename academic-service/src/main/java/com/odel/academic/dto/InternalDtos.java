package com.odel.academic.dto;

import java.util.List;
import java.util.UUID;

/** What academic-service answers other services on /internal/academic/**. */
public final class InternalDtos {
    private InternalDtos() {}

    public record SessionRef(UUID id, String name, String status) {}

    public record ProgrammeRef(UUID id, String code, String name, List<Integer> levels, UUID departmentId, String departmentName, UUID facultyId) {}

    public record CentreRef(UUID id, String code, String name) {}

    public record Reference(SessionRef currentSession, List<SessionRef> sessions, List<ProgrammeRef> programmes, List<CentreRef> centres) {}

    public record Scope(String scopeType, UUID id, String label) {}

    public record Places(List<StaffingDtos.UserPlace> items) {}
}
