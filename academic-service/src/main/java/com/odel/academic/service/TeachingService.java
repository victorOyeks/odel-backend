package com.odel.academic.service;

import com.odel.academic.dto.TeachingDtos.*;
import com.odel.academic.entity.AcademicSession;
import com.odel.academic.entity.Course;
import com.odel.academic.entity.StaffAssignment;
import com.odel.academic.repository.CourseRepository;
import com.odel.academic.repository.SessionRepository;
import com.odel.academic.repository.StaffAssignmentRepository;
import com.odel.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** The catalogue with each course's lecturer and facilitators in a session (content-service, B3). */
@Service
public class TeachingService {
    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final CourseRepository courses;
    private final StaffAssignmentRepository staffing;

    public TeachingService(SessionRepository sessions, SessionService sessionService, CourseRepository courses, StaffAssignmentRepository staffing) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.courses = courses;
        this.staffing = staffing;
    }

    /** Every course with its teaching staff in the session (default: the current one); with userId, only the courses that person teaches. */
    @Transactional(readOnly = true)
    public Teaching teaching(UUID sessionId, UUID userId) {
        AcademicSession s = sessionId != null ? sessions.findById(sessionId).orElseThrow(ApiException::notFound) : sessionService.current().orElse(null);
        if (s == null) return new Teaching(null, List.of());
        Map<UUID, List<StaffAssignment>> byCourse = staffing.findBySessionId(s.getId()).stream()
                .filter(a -> a.getCourseId() != null && (a.getKind() == StaffAssignment.Kind.LECTURER || a.getKind() == StaffAssignment.Kind.FACILITATOR))
                .collect(Collectors.groupingBy(StaffAssignment::getCourseId));
        List<TaughtCourse> out = new ArrayList<>();
        for (Course c : courses.sorted()) {
            List<StaffAssignment> list = byCourse.getOrDefault(c.getId(), List.of());
            UUID lecturer = list.stream().filter(a -> a.getKind() == StaffAssignment.Kind.LECTURER).map(StaffAssignment::getUserId).findFirst().orElse(null);
            List<UUID> facilitators = list.stream().filter(a -> a.getKind() == StaffAssignment.Kind.FACILITATOR).map(StaffAssignment::getUserId).distinct().toList();
            if (userId != null && !userId.equals(lecturer) && !facilitators.contains(userId)) continue;
            out.add(new TaughtCourse(new CourseRef(c.getId(), c.getCode(), c.getTitle(), c.getUnits(), c.getLevel(), c.getSemester()), lecturer, facilitators));
        }
        return new Teaching(new SessionRef(s.getId(), s.getName()), out);
    }
}
