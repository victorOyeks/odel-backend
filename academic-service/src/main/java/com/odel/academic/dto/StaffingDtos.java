package com.odel.academic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: Staffing (W-16 …). */
public final class StaffingDtos {
    private StaffingDtos() {}

    public record Person(UUID id, String fullName, String status) {
        public static Person of(UUID id, Map<UUID, PeerDtos.Staff> staff) {
            PeerDtos.Staff s = staff.get(id);
            return s == null ? new Person(id, "Former staff member", "REMOVED") : new Person(id, s.fullName(), s.status());
        }
    }

    public record CourseRef(UUID id, String code, String title, int level, int semester) {}

    public record FacilitatorView(UUID centreId, String centreName, Person user) {}

    public record CourseRow(CourseRef course, Person lecturer, List<FacilitatorView> facilitators, Person examOfficer, boolean needsLecturer) {}

    public record CentreRef(UUID id, String name, String code) {}

    public record CentreRow(CentreRef centre, Person coordinator, long students, boolean needsCoordinator) {}

    public record SessionRef(UUID id, String name) {}

    public record View(SessionRef session, List<CourseRow> courses, List<CentreRow> centres, long needLecturer, long needCoordinator,
                       boolean studentsImported, String summary) {}

    /** Counts for the checklist and W-16's filter chips. */
    public record Gaps(boolean anyAssigned, long courses, long needLecturer, long centres, long needCoordinator) {}

    public record FacilitatorIn(UUID centreId, UUID userId) {}

    public record CourseStaffIn(UUID sessionId, UUID lecturerId, List<FacilitatorIn> facilitators, UUID examOfficerId, @JsonProperty("notify") Boolean sendEmail) {}

    public record CentreStaffIn(UUID sessionId, UUID coordinatorId, @JsonProperty("notify") Boolean sendEmail) {}

    public record CopyIn(UUID fromSessionId, UUID toSessionId) {}

    public record Skipped(String what, String reason) {}

    public record CopyResult(int copied, List<Skipped> skipped, String from, String to) {}

    public record Option(UUID id, String fullName, String email, String status, String scopeLabel) {}

    public record UserPlace(String kind, String what, String session, boolean current) {}
}
