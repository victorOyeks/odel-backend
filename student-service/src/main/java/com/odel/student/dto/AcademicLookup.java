package com.odel.student.dto;

import java.util.List;
import java.util.UUID;

/**
 * What student-service needs from academic-service to check an import (GET /internal/academic/lookup): the programme codes
 * with the levels they admit, the study centres, and the current session.
 */
public record AcademicLookup(Session currentSession, List<Programme> programmes, List<Centre> centres) {
    public record Session(UUID id, String name) {}

    public record Programme(UUID id, String code, String name, List<Integer> levels) {}

    public record Centre(UUID id, String code, String name, String shortName, String phone) {}

    public static AcademicLookup empty() {
        return new AcademicLookup(null, List.of(), List.of());
    }
}
