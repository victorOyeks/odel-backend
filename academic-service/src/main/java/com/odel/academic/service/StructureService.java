package com.odel.academic.service;

import com.odel.academic.dto.StructureDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.academic.client.*;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

/** Faculties → departments → programmes (W-12 … W-12j). */
@Service
public class StructureService {
    private final FacultyRepository faculties;
    private final DepartmentRepository departments;
    private final ProgrammeRepository programmes;
    private final CourseRepository courses;
    private final CourseProgrammeRepository offers;
    private final StaffAssignmentRepository assignments;
    private final SessionService sessions;
    private final GradingService grading;
    private final AuditService audit;
    private final Clock clock;

    public StructureService(FacultyRepository faculties, DepartmentRepository departments, ProgrammeRepository programmes,
                            CourseRepository courses, CourseProgrammeRepository offers, StaffAssignmentRepository assignments,
                            SessionService sessions, GradingService grading, AuditService audit, Clock clock) {
        this.faculties = faculties;
        this.departments = departments;
        this.programmes = programmes;
        this.courses = courses;
        this.offers = offers;
        this.assignments = assignments;
        this.sessions = sessions;
        this.grading = grading;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- views ----

    @Transactional(readOnly = true)
    public Tree tree() {
        Map<UUID, Long> courseCount = offers.findAll().stream().collect(Collectors.groupingBy(CourseProgramme::getProgrammeId, Collectors.counting()));
        Map<UUID, List<Programme>> progs = programmes.sorted().stream().collect(Collectors.groupingBy(Programme::getDepartmentId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<Department>> depts = departments.sorted().stream().collect(Collectors.groupingBy(Department::getFacultyId, LinkedHashMap::new, Collectors.toList()));
        int d = 0, p = 0;
        List<FacultyRow> rows = new ArrayList<>();
        for (Faculty f : faculties.sorted()) {
            List<DepartmentRow> dr = new ArrayList<>();
            for (Department dep : depts.getOrDefault(f.getId(), List.of())) {
                List<ProgrammeRow> pr = progs.getOrDefault(dep.getId(), List.of()).stream().map(x -> programmeRow(x, courseCount.getOrDefault(x.getId(), 0L))).toList();
                dr.add(new DepartmentRow(dep.getId(), dep.getName(), dep.getCode(), pr.size(), pr));
                p += pr.size();
            }
            d += dr.size();
            rows.add(new FacultyRow(f.getId(), f.getName(), f.getCode(), dr.size(), dr));
        }
        return new Tree(rows, new Totals(rows.size(), d, p));
    }

    static ProgrammeRow programmeRow(Programme x, long courseCount) {
        return new ProgrammeRow(x.getId(), x.getName(), x.getCode(), x.getDegree(), x.getDurationYears(), x.levels(), x.entryLevelList(),
                x.getMinUnitsToGraduate(), x.getStatus(), courseCount);
    }

    @Transactional(readOnly = true)
    public ProgrammeDetail programme(UUID id) {
        Programme p = programmes.findById(id).orElseThrow(ApiException::notFound);
        Department d = departments.findById(p.getDepartmentId()).orElseThrow();
        Faculty f = faculties.findById(d.getFacultyId()).orElseThrow();
        Set<UUID> withLecturer = sessions.current().map(s -> assignments.findBySessionId(s.getId()).stream()
                .filter(a -> a.getKind() == StaffAssignment.Kind.LECTURER).map(StaffAssignment::getCourseId).collect(Collectors.toSet())).orElse(Set.of());
        Map<String, List<Course>> grouped = new TreeMap<>();
        for (CourseProgramme o : offers.findByProgrammeId(id)) {
            courses.findById(o.getCourseId()).ifPresent(c -> grouped.computeIfAbsent(String.format("%03d-%d", c.getLevel(), c.getSemester()), k -> new ArrayList<>()).add(c));
        }
        List<LevelRow> byLevel = grouped.values().stream().map(list -> new LevelRow(list.get(0).getLevel(), list.get(0).getSemester(), list.size(),
                list.stream().mapToInt(Course::getUnits).sum(), (int) list.stream().filter(c -> withLecturer.contains(c.getId())).count())).toList();
        String rules = grading.labelFor(id);
        return new ProgrammeDetail(p.getId(), p.getName(), p.getCode(), p.getDegree(), p.getDurationYears(),
                p.getDurationYears() + (p.getDurationYears() == 1 ? " year · " : " years · ") + (p.getDurationYears() * 2) + " semesters",
                p.levels(), p.entryLevelList(), p.entryLevelList().stream().map(l -> l + "L").collect(Collectors.joining(", ")),
                p.getMinUnitsToGraduate(), p.getStatus(), rules, new Ref(d.getId(), d.getName()), new Ref(f.getId(), f.getName()), byLevel);
    }

    // ---- writes ----

    @Transactional
    public Faculty saveFaculty(UUID id, FacultyIn in, AuditService.Actor actor) {
        Map<String, String> f = new LinkedHashMap<>();
        if (Codes.blank(in.name())) f.put("name", "Enter the faculty's name, for example Faculty of Science.");
        if (Codes.blank(in.code()) || !Codes.unit(in.code()).matches(Codes.UNIT)) f.put("code", "Use a short code of letters and numbers, for example SCI.");
        fail(f);
        String code = Codes.unit(in.code()), name = in.name().trim();
        faculties.findByCode(code).filter(x -> !x.getId().equals(id)).ifPresent(x -> taken(code, x.getName()));
        faculties.findByName(name).filter(x -> !x.getId().equals(id)).ifPresent(x -> nameTaken("a faculty called " + name));
        Faculty x = id == null ? new Faculty() : faculties.findById(id).orElseThrow(ApiException::notFound);
        String before = x.getName();
        if (id == null) { x.setId(UUID.randomUUID()); x.setCreatedAt(clock.instant()); }
        x.setName(name);
        x.setCode(code);
        x.setUpdatedAt(clock.instant());
        faculties.saveAndFlush(x);
        record(actor, id == null ? "FACULTY_CREATED" : "FACULTY_UPDATED", "faculty", x.getId(),
                id == null ? "Added the faculty " + name : (Objects.equals(before, name) ? "Edited the faculty " + name : "Renamed the faculty " + before + " to " + name));
        return x;
    }

    @Transactional
    public Department saveDepartment(UUID id, DepartmentIn in, AuditService.Actor actor) {
        Map<String, String> f = new LinkedHashMap<>();
        if (in.facultyId() == null || faculties.findById(in.facultyId()).isEmpty()) f.put("facultyId", "Choose the faculty this department belongs to.");
        if (Codes.blank(in.name())) f.put("name", "Enter the department's name.");
        if (Codes.blank(in.code()) || !Codes.unit(in.code()).matches(Codes.UNIT)) f.put("code", "Use a short code of letters and numbers, for example CSC.");
        fail(f);
        String code = Codes.unit(in.code()), name = in.name().trim();
        departments.findByCode(code).filter(x -> !x.getId().equals(id)).ifPresent(x -> taken(code, x.getName()));
        departments.findByFacultyAndName(in.facultyId(), name).filter(x -> !x.getId().equals(id)).ifPresent(x -> nameTaken("a department called " + name + " in this faculty"));
        Department x = id == null ? new Department() : departments.findById(id).orElseThrow(ApiException::notFound);
        String before = x.getName();
        if (id == null) { x.setId(UUID.randomUUID()); x.setCreatedAt(clock.instant()); }
        x.setFacultyId(in.facultyId());
        x.setName(name);
        x.setCode(code);
        x.setUpdatedAt(clock.instant());
        departments.saveAndFlush(x);
        record(actor, id == null ? "DEPARTMENT_CREATED" : "DEPARTMENT_UPDATED", "department", x.getId(),
                id == null ? "Added the department " + name : (Objects.equals(before, name) ? "Edited the department " + name : "Renamed the department " + before + " to " + name));
        return x;
    }

    /** Checks a programme's details; also used row by row by the structure import. */
    static Map<String, String> checkProgramme(String name, String code, String degree, Integer duration, List<Integer> entry) {
        Map<String, String> f = new LinkedHashMap<>();
        if (Codes.blank(name)) f.put("name", "Enter the programme's name, for example B.Sc. Computer Science.");
        if (Codes.blank(code) || !Codes.unit(code).matches(Codes.UNIT)) f.put("code", "Use a short code of letters, numbers and hyphens, for example BSC-CSC.");
        if (Codes.blank(degree)) f.put("degree", "Enter the degree, for example B.Sc.");
        if (duration == null || duration < 1 || duration > 7) f.put("durationYears", "Enter a duration from 1 to 7 years.");
        else if (entry != null) {
            for (Integer l : entry) {
                if (l == null || l % 100 != 0 || l < 100 || l > duration * 100) {
                    f.put("entryLevels", "Entry levels must be among the programme's levels (100L to " + duration * 100 + "L).");
                    break;
                }
            }
        }
        return f;
    }

    @Transactional
    public Programme saveProgramme(UUID id, ProgrammeIn in, AuditService.Actor actor) {
        Map<String, String> f = new LinkedHashMap<>();
        if (in.departmentId() == null || departments.findById(in.departmentId()).isEmpty()) f.put("departmentId", "Choose the department this programme belongs to.");
        f.putAll(checkProgramme(in.name(), in.code(), in.degree(), in.durationYears(), in.entryLevels()));
        if (in.minUnitsToGraduate() != null && (in.minUnitsToGraduate() < 1 || in.minUnitsToGraduate() > 600)) f.put("minUnitsToGraduate", "Enter a number of units from 1 to 600, or leave it empty.");
        fail(f);
        String code = Codes.unit(in.code()), name = in.name().trim();
        programmes.findByCode(code).filter(x -> !x.getId().equals(id)).ifPresent(x -> taken(code, x.getName()));
        programmes.findByDepartmentAndName(in.departmentId(), name).filter(x -> !x.getId().equals(id)).ifPresent(x -> nameTaken("a programme called " + name + " in this department"));
        Programme x = id == null ? new Programme() : programmes.findById(id).orElseThrow(ApiException::notFound);
        if (id == null) { x.setId(UUID.randomUUID()); x.setCreatedAt(clock.instant()); x.setStatus("ACTIVE"); }
        x.setDepartmentId(in.departmentId());
        x.setName(name);
        x.setCode(code);
        x.setDegree(in.degree().trim());
        x.setDurationYears(in.durationYears());
        List<Integer> entry = in.entryLevels() == null || in.entryLevels().isEmpty() ? List.of(100) : in.entryLevels().stream().distinct().sorted().toList();
        x.setEntryLevels(entry.stream().map(String::valueOf).collect(Collectors.joining(",")));
        x.setMinUnitsToGraduate(in.minUnitsToGraduate());
        x.setUpdatedAt(clock.instant());
        programmes.saveAndFlush(x);
        record(actor, id == null ? "PROGRAMME_CREATED" : "PROGRAMME_UPDATED", "programme", x.getId(), (id == null ? "Added the programme " : "Edited the programme ") + name);
        return x;
    }

    private void record(AuditService.Actor actor, String action, String type, UUID id, String summary) {
        audit.record(new AuditService.Entry(TenantContext.require(), actor, action, type, id, summary, null, null));
    }

    static void fail(Map<String, String> f) {
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Some details need fixing.", f);
    }

    static void taken(String code, String by) {
        throw new ApiException(HttpStatus.CONFLICT, "code_taken", code + " is already used by " + by + ". Choose another code.");
    }

    static void nameTaken(String what) {
        throw new ApiException(HttpStatus.CONFLICT, "name_taken", "There is already " + what + ".");
    }
}
