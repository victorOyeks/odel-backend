package com.odel.academic.service;

import com.odel.academic.dto.CourseDtos.*;
import com.odel.academic.dto.PeerDtos.*;
import com.odel.academic.dto.StaffingDtos.Person;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.academic.client.*;

import com.odel.common.ApiException;
import com.odel.common.PageResponse;
import com.odel.common.audit.AuditService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

/** The course catalogue (W-13 … W-13e). Courses carry over between sessions; who teaches them is Staffing. */
@Service
public class CourseService {
    private final CourseRepository courses;
    private final CourseProgrammeRepository offers;
    private final ProgrammeRepository programmes;
    private final StaffAssignmentRepository assignments;
    private final SessionService sessions;
    private final StaffingService staffing;
    private final IdentityClient identity;
    private final AuditService audit;
    private final Clock clock;

    public CourseService(CourseRepository courses, CourseProgrammeRepository offers, ProgrammeRepository programmes,
                         StaffAssignmentRepository assignments, SessionService sessions, StaffingService staffing, IdentityClient identity,
                         AuditService audit, Clock clock) {
        this.courses = courses;
        this.offers = offers;
        this.programmes = programmes;
        this.assignments = assignments;
        this.sessions = sessions;
        this.staffing = staffing;
        this.identity = identity;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CoursePage list(String search, UUID programmeId, Integer level, Integer semester, String lecturer, int page, int size) {
        int s = Math.min(Math.max(size, 1), 200), p = Math.max(page, 0);
        Map<UUID, String> progNames = programmes.findAll().stream().collect(Collectors.toMap(Programme::getId, Programme::getName));
        Map<UUID, List<CourseProgramme>> offerMap = offers.findAll().stream().collect(Collectors.groupingBy(CourseProgramme::getCourseId));
        Map<UUID, UUID> lecturers = lecturers();
        Map<UUID, Staff> staff = lecturers.isEmpty() ? Map.of() : identity.staffById();
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String qCode = q.replace(" ", "");
        List<Course> all = courses.sorted();
        List<Course> filtered = all.stream()
                .filter(c -> q.isEmpty() || c.getCode().toLowerCase(Locale.ROOT).replace(" ", "").contains(qCode) || c.getTitle().toLowerCase(Locale.ROOT).contains(q))
                .filter(c -> programmeId == null || offerMap.getOrDefault(c.getId(), List.of()).stream().anyMatch(o -> o.getProgrammeId().equals(programmeId)))
                .filter(c -> level == null || c.getLevel() == level)
                .filter(c -> semester == null || c.getSemester() == semester)
                .filter(c -> lecturer == null || lecturer.isBlank() || lecturer.equalsIgnoreCase("any")
                        || (lecturer.equalsIgnoreCase("none") ? !lecturers.containsKey(c.getId()) : lecturer.equals(String.valueOf(lecturers.get(c.getId())))))
                .toList();
        List<CourseView> items = filtered.stream().skip((long) p * s).limit(s).map(c -> view(c, offerMap.getOrDefault(c.getId(), List.of()), progNames, lecturers, staff)).toList();
        return new CoursePage(items, p, s, filtered.size(), all.size(), progNames.size());
    }

    @Transactional(readOnly = true)
    public CourseView get(UUID id) {
        Course c = courses.findById(id).orElseThrow(ApiException::notFound);
        Map<UUID, String> progNames = programmes.findAll().stream().collect(Collectors.toMap(Programme::getId, Programme::getName));
        Map<UUID, UUID> lecturers = lecturers();
        return view(c, offers.findByCourseId(id), progNames, lecturers, lecturers.containsKey(id) ? identity.staffById() : Map.of());
    }

    /** Course → lecturer in the current session. */
    private Map<UUID, UUID> lecturers() {
        return sessions.current().map(s -> assignments.findBySessionId(s.getId()).stream().filter(a -> a.getKind() == StaffAssignment.Kind.LECTURER)
                .collect(Collectors.toMap(StaffAssignment::getCourseId, StaffAssignment::getUserId, (a, b) -> a))).orElse(Map.of());
    }

    private static CourseView view(Course c, List<CourseProgramme> offered, Map<UUID, String> progNames, Map<UUID, UUID> lecturers, Map<UUID, Staff> staff) {
        UUID lec = lecturers.get(c.getId());
        return new CourseView(c.getId(), c.getCode(), c.getTitle(), c.getUnits(), c.getLevel(), c.getSemester(), c.getSemester() == 1 ? "First" : "Second",
                offered.stream().map(o -> new Offer(o.getProgrammeId(), progNames.get(o.getProgrammeId()), o.getKind().name()))
                        .sorted(Comparator.comparing(Offer::programmeName)).toList(),
                lec == null ? null : Person.of(lec, staff), lec == null);
    }

    /** Row and form checks for one course (also used by the catalogue import). */
    static Map<String, String> check(String code, String title, Integer units, Integer level, Integer semester) {
        Map<String, String> f = new LinkedHashMap<>();
        if (Codes.course(code) == null) f.put("code", "Write the code as letters then numbers, for example CSC 201.");
        if (Codes.blank(title)) f.put("title", "Enter the course title.");
        else if (title.trim().length() > 200) f.put("title", "Keep the title under 200 characters.");
        if (units == null || units < 1 || units > 12) f.put("units", "Enter units from 1 to 12.");
        if (level == null || level < 100 || level > 900 || level % 100 != 0) f.put("level", "Choose a level such as 100L or 200L.");
        if (semester == null || (semester != 1 && semester != 2)) f.put("semester", "Choose the first or second semester.");
        return f;
    }

    @Transactional
    public CourseView save(UUID id, CourseIn in, AuditService.Actor actor) {
        if (id != null) courses.findById(id).orElseThrow(ApiException::notFound); // another university's id: 404 before any checks
        Map<String, String> f = check(in.code(), in.title(), in.units(), in.level(), in.semester());
        List<OfferIn> offered = in.offeredTo() == null ? List.of() : in.offeredTo();
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < offered.size(); i++) {
            OfferIn o = offered.get(i);
            Optional<Programme> prog = o == null || o.programmeId() == null ? Optional.empty() : programmes.findById(o.programmeId());
            if (prog.isEmpty()) { f.put("offeredTo[" + i + "].programmeId", "Choose a programme from Academic structure."); continue; }
            if (!seen.add(o.programmeId())) f.put("offeredTo[" + i + "].programmeId", prog.get().getName() + " is listed twice.");
            if (kind(o.kind()) == null) f.put("offeredTo[" + i + "].kind", "Choose Core or Elective.");
            if (in.level() != null && in.level() > prog.get().getDurationYears() * 100) {
                f.put("level", prog.get().getName() + " only runs to " + prog.get().getDurationYears() * 100 + "L.");
            }
        }
        StructureService.fail(f);
        String code = Codes.course(in.code());
        courses.findByCode(code).filter(x -> !x.getId().equals(id)).ifPresent(x -> {
            throw new ApiException(HttpStatus.CONFLICT, "code_taken", x.getCode() + " is already in the catalogue (" + x.getTitle() + ").");
        });
        Course c = id == null ? new Course() : courses.findById(id).orElseThrow(ApiException::notFound);
        if (id == null) { c.setId(UUID.randomUUID()); c.setCreatedAt(clock.instant()); }
        c.setCode(code);
        c.setTitle(in.title().trim());
        c.setUnits(in.units());
        c.setLevel(in.level());
        c.setSemester(in.semester());
        c.setUpdatedAt(clock.instant());
        courses.saveAndFlush(c);
        offers.deleteByCourseId(c.getId());
        offers.flush();
        for (OfferIn o : offered) {
            CourseProgramme cp = new CourseProgramme();
            cp.setId(UUID.randomUUID());
            cp.setCourseId(c.getId());
            cp.setProgrammeId(o.programmeId());
            cp.setKind(kind(o.kind()));
            offers.save(cp);
        }
        offers.flush();
        audit.record(new AuditService.Entry(TenantContext.require(), actor, id == null ? "COURSE_CREATED" : "COURSE_UPDATED", "course", c.getId(),
                (id == null ? "Added " : "Edited ") + c.getCode() + " · " + c.getTitle() + " to the catalogue", null,
                Map.of("units", c.getUnits(), "level", c.getLevel(), "semester", c.getSemester())));
        if (in.lecturerId() != null) staffing.setLecturer(c.getId(), in.lecturerId(), actor);
        return get(c.getId());
    }

    static CourseProgramme.Kind kind(String k) {
        if (k == null) return null;
        return switch (k.trim().toLowerCase(Locale.ROOT)) {
            case "compulsory", "core", "c" -> CourseProgramme.Kind.COMPULSORY;
            case "elective", "e" -> CourseProgramme.Kind.ELECTIVE;
            default -> null;
        };
    }
}
