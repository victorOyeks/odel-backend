package com.odel.registration.service;

import com.odel.registration.dto.InternalDtos.*;
import com.odel.registration.entity.Registration;
import com.odel.registration.entity.RegistrationRequest;
import com.odel.registration.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/** What other services ask registration-service: a student's courses and summary, the university's counts. */
@Service
public class InternalService {
    private final CalendarService calendar;
    private final RegistrationRepository registrations;
    private final RegistrationCourseRepository courses;
    private final RequestRepository requests;
    private final TransactionTemplate tx;

    public InternalService(CalendarService calendar, RegistrationRepository registrations, RegistrationCourseRepository courses,
                           RequestRepository requests, TransactionTemplate tx) {
        this.calendar = calendar;
        this.registrations = registrations;
        this.courses = courses;
        this.requests = requests;
        this.tx = tx;
    }

    public StudentCourses courses(UUID studentId, UUID semesterId) {
        UUID sem = semesterId != null ? semesterId : calendar.pick(null).semesterId();
        if (sem == null) return new StudentCourses(false, null, null, null, List.of(), 0);
        return tx.execute(t -> registrations.forStudent(studentId, sem).map(r -> new StudentCourses(true, sem, r.getId(), r.getSlipNumber(),
                Views.ordered(courses.forRegistration(r.getId())).stream().map(c -> new Course(c.getCourseId(), c.getCode(), c.getTitle(), c.getUnits(), c.getKind().name())).toList(),
                r.getTotalUnits())).orElse(new StudentCourses(false, sem, null, null, List.of(), 0)));
    }

    /** Everyone registered for a course in a semester (default: the current one), by name. */
    public CourseStudents courseStudents(UUID courseId, UUID semesterId) {
        UUID sem = semesterId != null ? semesterId : calendar.pick(null).semesterId();
        if (sem == null) return new CourseStudents(null, List.of());
        return tx.execute(t -> {
            Set<UUID> ids = new HashSet<>(courses.registrationIdsForCourse(courseId));
            return new CourseStudents(sem, registrations.forSemester(sem).stream().filter(r -> ids.contains(r.getId()))
                    .sorted(Comparator.comparing(Registration::getStudentName, String.CASE_INSENSITIVE_ORDER))
                    .map(r -> new CourseStudent(r.getStudentId(), r.getUserId(), r.getMatricNumber(), r.getStudentName(), r.getCentreId(),
                            r.getCentreCode(), r.getCentreName())).toList());
        });
    }

    public StudentSummary studentSummary(UUID studentId) {
        CalendarService.Choice c = calendar.pick(null);
        if (c.none()) return new StudentSummary(null, null, c.window().state().name(), false, 0, 0, null, 0);
        return tx.execute(t -> {
            Optional<Registration> r = registrations.forStudent(studentId, c.semesterId());
            long pending = requests.forStudent(studentId, c.semesterId()).stream().filter(x -> x.getStatus() == RegistrationRequest.Status.PENDING).count();
            return new StudentSummary(c.semesterId(), CalendarService.label(c.semester().sessionName(), c.semester().number()), c.window().state().name(),
                    r.isPresent(), r.map(Registration::getCourseCount).orElse(0), r.map(Registration::getTotalUnits).orElse(0),
                    r.map(Registration::getSlipNumber).orElse(null), pending);
        });
    }

    public Summary summary(UUID semesterId) {
        UUID sem = semesterId != null ? semesterId : calendar.pick(null).semesterId();
        if (sem == null) return new Summary(null, 0, 0, 0, List.of());
        return tx.execute(t -> {
            List<Registration> regs = registrations.forSemester(sem);
            List<RegistrationRequest> reqs = requests.forSemester(sem).stream().filter(r -> r.getStatus() == RegistrationRequest.Status.PENDING).toList();
            Map<UUID, Long> byCentre = regs.stream().filter(r -> r.getCentreId() != null).collect(Collectors.groupingBy(Registration::getCentreId, Collectors.counting()));
            return new Summary(sem, regs.size(), reqs.stream().filter(r -> r.getKind() == RegistrationRequest.Kind.LATE).count(),
                    reqs.stream().filter(r -> r.getKind() == RegistrationRequest.Kind.CHANGE).count(),
                    byCentre.entrySet().stream().map(e -> new CentreCount(e.getKey(), e.getValue())).toList());
        });
    }
}
