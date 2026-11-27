package com.odel.student.client;

import com.odel.student.dto.AcademicLookup;

/**
 * Programmes (with the levels they admit), study centres and the current session of the current university, from
 * academic-service (GET /internal/academic/lookup). Two implementations: the real call, and a stub until academic-service
 * is merged (odel.student.academic-lookup = http | stub).
 */
public interface AcademicDirectory {
    AcademicLookup lookup();
}
