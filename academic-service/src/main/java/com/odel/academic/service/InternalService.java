package com.odel.academic.service;

import com.odel.academic.dto.InternalDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Answers for other services: reference data for the students import, scope labels and checks for identity-service. */
@Service
public class InternalService {
    private final SessionService sessions;
    private final SessionRepository sessionRepo;
    private final FacultyRepository faculties;
    private final DepartmentRepository departments;
    private final ProgrammeRepository programmes;
    private final CourseRepository courses;
    private final CentreRepository centres;
    private final StaffingService staffing;

    public InternalService(SessionService sessions, SessionRepository sessionRepo, FacultyRepository faculties, DepartmentRepository departments,
                           ProgrammeRepository programmes, CourseRepository courses, CentreRepository centres, StaffingService staffing) {
        this.sessions = sessions;
        this.sessionRepo = sessionRepo;
        this.faculties = faculties;
        this.departments = departments;
        this.programmes = programmes;
        this.courses = courses;
        this.centres = centres;
        this.staffing = staffing;
    }

    @Transactional(readOnly = true)
    public Reference reference() {
        Map<UUID, Department> depts = departments.findAll().stream().collect(Collectors.toMap(Department::getId, Function.identity()));
        return new Reference(
                sessions.current().map(s -> new SessionRef(s.getId(), s.getName(), s.getStatus().name())).orElse(null),
                sessionRepo.newestFirst().stream().map(s -> new SessionRef(s.getId(), s.getName(), s.getStatus().name())).toList(),
                programmes.sorted().stream().map(p -> {
                    Department d = depts.get(p.getDepartmentId());
                    return new ProgrammeRef(p.getId(), p.getCode(), p.getName(), p.levels(), d.getId(), d.getName(), d.getFacultyId());
                }).toList(),
                centres.sorted().stream().map(c -> new CentreRef(c.getId(), c.getCode(), c.getName())).toList());
    }

    /** "CSC 201 · Computer Science", "Akure Study Centre", "Department of Computer Science", "Faculty of Science". */
    @Transactional(readOnly = true)
    public Scope scope(String type, UUID id) {
        String t = type.toUpperCase(Locale.ROOT);
        String label = switch (t) {
            case "FACULTY" -> faculties.findById(id).map(Faculty::getName).orElse(null);
            case "DEPARTMENT" -> departments.findById(id).map(Department::getName).orElse(null);
            case "CENTRE" -> centres.findById(id).map(Centre::getName).orElse(null);
            case "COURSE" -> courses.findById(id).map(staffing::courseLabel).orElse(null);
            case "PROGRAMME" -> programmes.findById(id).map(Programme::getName).orElse(null);
            default -> null;
        };
        if (label == null) throw ApiException.notFound();
        return new Scope(t, id, label);
    }

    /** A scope by its code (bulk invite CSV): course CSC201, centre AKR, department CSC, faculty SCI. */
    @Transactional(readOnly = true)
    public Scope resolve(String type, String code) {
        String t = type.toUpperCase(Locale.ROOT);
        UUID id = switch (t) {
            case "FACULTY" -> faculties.findByCode(Codes.unit(code)).map(Faculty::getId).orElse(null);
            case "DEPARTMENT" -> departments.findByCode(Codes.unit(code)).map(Department::getId).orElse(null);
            case "CENTRE" -> centres.findByCode(Codes.unit(code)).map(Centre::getId).orElse(null);
            case "COURSE" -> Codes.course(code) == null ? null : courses.findByCode(Codes.course(code)).map(Course::getId).orElse(null);
            default -> null;
        };
        if (id == null) throw ApiException.notFound();
        return scope(t, id);
    }

    public Places places(UUID userId) {
        return new Places(staffing.forUser(userId));
    }
}
