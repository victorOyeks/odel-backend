package com.odel.academic.service;

import com.odel.academic.dto.RegistrationSupportDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The calendar, course offerings and centre coordinators, for registration-service (B2). */
@Service
public class RegistrationSupportService {
    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final SemesterRepository semesters;
    private final CourseRepository courses;
    private final CourseProgrammeRepository offerings;
    private final CentreRepository centres;
    private final StaffAssignmentRepository staffing;

    public RegistrationSupportService(SessionRepository sessions, SessionService sessionService, SemesterRepository semesters,
                                      CourseRepository courses, CourseProgrammeRepository offerings, CentreRepository centres,
                                      StaffAssignmentRepository staffing) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.semesters = semesters;
        this.courses = courses;
        this.offerings = offerings;
        this.centres = centres;
        this.staffing = staffing;
    }

    /** The ACTIVE session and its semesters (students only ever register in the active session). */
    @Transactional(readOnly = true)
    public Calendar calendar() {
        return new Calendar(sessions.findFirstByStatus(AcademicSession.Status.ACTIVE).map(s -> new CalendarSession(s.getId(), s.getName(),
                s.getStatus().name(), semesters.findBySessionIdOrderByNumber(s.getId()).stream().map(x -> info(s, x)).toList())).orElse(null));
    }

    @Transactional(readOnly = true)
    public SemesterInfo semester(UUID id) {
        Semester x = semesters.findById(id).orElseThrow(ApiException::notFound);
        AcademicSession s = sessions.findById(x.getSessionId()).orElseThrow(ApiException::notFound);
        return info(s, x);
    }

    /** Courses offered to a programme at a level in a semester (1 or 2), with compulsory or elective. */
    @Transactional(readOnly = true)
    public Courses offerings(UUID programmeId, int level, int semester) {
        List<CourseProgramme> links = offerings.findByProgrammeId(programmeId);
        Map<UUID, CourseProgramme.Kind> kinds = links.stream().collect(Collectors.toMap(CourseProgramme::getCourseId, CourseProgramme::getKind, (a, b) -> a));
        if (kinds.isEmpty()) return new Courses(List.of());
        return new Courses(courses.findAllInTenant(kinds.keySet()).stream()
                .filter(c -> c.getLevel() == level && c.getSemester() == semester)
                .sorted(Comparator.comparing((Course c) -> kinds.get(c.getId())).thenComparing(Course::getCode))
                .map(c -> course(c, kinds.get(c.getId()).name())).toList());
    }

    @Transactional(readOnly = true)
    public Courses courses(Collection<UUID> ids, String code) {
        if (code != null && !code.isBlank()) {
            String c = Codes.course(code);
            return new Courses(c == null ? List.of() : courses.findByCode(c).map(x -> List.of(course(x, null))).orElse(List.of()));
        }
        if (ids == null || ids.isEmpty()) return new Courses(List.of());
        return new Courses(courses.findAllInTenant(ids).stream().sorted(Comparator.comparing(Course::getCode)).map(x -> course(x, null)).toList());
    }

    /** Every centre with its coordinator in a session (default: the current session); userId null when it has none. */
    @Transactional(readOnly = true)
    public Coordinators coordinators(UUID sessionId) {
        AcademicSession s = sessionId != null ? sessions.findById(sessionId).orElseThrow(ApiException::notFound) : sessionService.current().orElse(null);
        Map<UUID, UUID> byCentre = s == null ? Map.of() : staffing.findBySessionId(s.getId()).stream()
                .filter(a -> a.getKind() == StaffAssignment.Kind.COORDINATOR && a.getCentreId() != null)
                .collect(Collectors.toMap(StaffAssignment::getCentreId, StaffAssignment::getUserId, (a, b) -> a));
        return new Coordinators(s == null ? null : new SessionRef(s.getId(), s.getName()),
                centres.sorted().stream().map(c -> new CentreCoordinator(c.getId(), c.getCode(), c.getName(), byCentre.get(c.getId()))).toList());
    }

    private static SemesterInfo info(AcademicSession s, Semester x) {
        return new SemesterInfo(x.getId(), s.getId(), s.getName(), s.getStatus().name(), x.getNumber(),
                x.getNumber() == 1 ? "First semester" : "Second semester", x.getStartsOn(), x.getEndsOn(), x.getRegOpensOn(),
                x.getRegClosesOn(), x.getLateRegClosesOn());
    }

    private static CourseInfo course(Course c, String kind) {
        return new CourseInfo(c.getId(), c.getCode(), c.getTitle(), c.getUnits(), c.getLevel(), c.getSemester(), kind);
    }
}
