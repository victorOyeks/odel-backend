package com.odel.academic.service;

import com.odel.academic.dto.StaffingDtos.*;
import com.odel.academic.dto.PeerDtos.*;
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

/**
 * Staffing (W-16 … W-16e): per session, who teaches each course (lecturer, one facilitator per centre, exam officer) and
 * who coordinates each centre. Staff come from People & roles in identity-service and must hold the matching role.
 */
@Service
public class StaffingService {
    private final StaffAssignmentRepository assignments;
    private final CourseRepository courses;
    private final CentreRepository centres;
    private final SessionRepository sessionRepo;
    private final CourseProgrammeRepository offers;
    private final ProgrammeRepository programmes;
    private final DepartmentRepository departments;
    private final SessionService sessions;
    private final IdentityClient identity;
    private final StudentsClient studentsClient;
    private final NotificationClient notifications;
    private final AuditService audit;
    private final Clock clock;

    public StaffingService(StaffAssignmentRepository assignments, CourseRepository courses, CentreRepository centres,
                           SessionRepository sessionRepo, CourseProgrammeRepository offers, ProgrammeRepository programmes,
                           DepartmentRepository departments, SessionService sessions, IdentityClient identity, StudentsClient students, NotificationClient notifications, AuditService audit, Clock clock) {
        this.assignments = assignments;
        this.courses = courses;
        this.centres = centres;
        this.sessionRepo = sessionRepo;
        this.offers = offers;
        this.programmes = programmes;
        this.departments = departments;
        this.sessions = sessions;
        this.identity = identity;
        this.studentsClient = students;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    static final Map<StaffAssignment.Kind, String> ROLE = Map.of(
            StaffAssignment.Kind.LECTURER, "LECTURER", StaffAssignment.Kind.FACILITATOR, "FACILITATOR",
            StaffAssignment.Kind.EXAM_OFFICER, "EXAM_OFFICER", StaffAssignment.Kind.COORDINATOR, "CENTRE_COORDINATOR");
    static final Map<StaffAssignment.Kind, String> LABEL = Map.of(
            StaffAssignment.Kind.LECTURER, "Lecturer", StaffAssignment.Kind.FACILITATOR, "Facilitator",
            StaffAssignment.Kind.EXAM_OFFICER, "Exam officer", StaffAssignment.Kind.COORDINATOR, "Centre coordinator");

    @Transactional(readOnly = true)
    public Gaps gaps(AcademicSession s) {
        List<StaffAssignment> all = assignments.findBySessionId(s.getId());
        Set<UUID> lectured = all.stream().filter(a -> a.getKind() == StaffAssignment.Kind.LECTURER).map(StaffAssignment::getCourseId).collect(Collectors.toSet());
        Set<UUID> coordinated = all.stream().filter(a -> a.getKind() == StaffAssignment.Kind.COORDINATOR).map(StaffAssignment::getCentreId).collect(Collectors.toSet());
        List<Course> cs = courses.findAll();
        List<Centre> ce = centres.findAll();
        return new Gaps(!all.isEmpty(), cs.size(), cs.stream().filter(c -> !lectured.contains(c.getId())).count(),
                ce.size(), ce.stream().filter(c -> !coordinated.contains(c.getId())).count());
    }

    @Transactional(readOnly = true)
    public View view(UUID sessionId) {
        AcademicSession s = sessions.resolve(sessionId);
        Map<UUID, Staff> staff = identity.staffById();
        StudentsSummary students = studentsClient.summary();
        List<StaffAssignment> all = assignments.findBySessionId(s.getId());
        Map<UUID, Centre> centreById = centres.findAll().stream().collect(Collectors.toMap(Centre::getId, c -> c));
        Map<UUID, List<StaffAssignment>> byCourse = all.stream().filter(a -> a.getCourseId() != null).collect(Collectors.groupingBy(StaffAssignment::getCourseId));
        List<CourseRow> rows = courses.sorted().stream().map(c -> courseRow(c, byCourse.getOrDefault(c.getId(), List.of()), staff, centreById)).toList();
        Map<UUID, StaffAssignment> coord = all.stream().filter(a -> a.getKind() == StaffAssignment.Kind.COORDINATOR)
                .collect(Collectors.toMap(StaffAssignment::getCentreId, a -> a, (a, b) -> a));
        List<CentreRow> centreRows = centres.sorted().stream().map(c -> {
            StaffAssignment a = coord.get(c.getId());
            return new CentreRow(new CentreRef(c.getId(), c.getName(), c.getCode()), a == null ? null : Person.of(a.getUserId(), staff),
                    students.atCentre(c.getId()), a == null);
        }).toList();
        long needL = rows.stream().filter(CourseRow::needsLecturer).count();
        long needC = centreRows.stream().filter(CentreRow::needsCoordinator).count();
        return new View(new SessionRef(s.getId(), s.getName()), rows, centreRows, needL, needC, students.total() > 0, summary(rows.size(), needL, centreRows.size(), needC));
    }

    static String summary(long courses, long needL, long centres, long needC) {
        List<String> parts = new ArrayList<>();
        if (courses > 0) parts.add(needL == 0 ? "All " + courses + (courses == 1 ? " course has a lecturer" : " courses have a lecturer")
                : needL + (needL == 1 ? " course still needs a lecturer" : " courses still need a lecturer"));
        if (centres > 0 && needC > 0) parts.add(needC + (needC == 1 ? " centre needs a coordinator" : " centres need a coordinator"));
        return parts.isEmpty() ? "Who teaches each course and runs each centre" : String.join(" · ", parts);
    }

    private CourseRow courseRow(Course c, List<StaffAssignment> list, Map<UUID, Staff> staff, Map<UUID, Centre> centreById) {
        Person lecturer = list.stream().filter(a -> a.getKind() == StaffAssignment.Kind.LECTURER).findFirst().map(a -> Person.of(a.getUserId(), staff)).orElse(null);
        Person exam = list.stream().filter(a -> a.getKind() == StaffAssignment.Kind.EXAM_OFFICER).findFirst().map(a -> Person.of(a.getUserId(), staff)).orElse(null);
        List<FacilitatorView> fac = list.stream().filter(a -> a.getKind() == StaffAssignment.Kind.FACILITATOR)
                .map(a -> new FacilitatorView(a.getCentreId(), Optional.ofNullable(centreById.get(a.getCentreId())).map(Centre::getName).orElse(""), Person.of(a.getUserId(), staff)))
                .sorted(Comparator.comparing(FacilitatorView::centreName)).toList();
        return new CourseRow(new CourseRef(c.getId(), c.getCode(), c.getTitle(), c.getLevel(), c.getSemester()), lecturer, fac, exam, lecturer == null);
    }

    @Transactional
    public CourseRow setCourse(UUID courseId, CourseStaffIn in, AuditService.Actor actor) {
        Course c = courses.findById(courseId).orElseThrow(ApiException::notFound);
        AcademicSession s = sessions.resolve(in.sessionId());
        Map<UUID, Staff> staff = identity.staffById();
        Map<String, String> f = new LinkedHashMap<>();
        check(staff, in.lecturerId(), StaffAssignment.Kind.LECTURER, "lecturerId", f);
        check(staff, in.examOfficerId(), StaffAssignment.Kind.EXAM_OFFICER, "examOfficerId", f);
        Set<UUID> seenCentres = new HashSet<>();
        List<FacilitatorIn> facs = in.facilitators() == null ? List.of() : in.facilitators();
        for (int i = 0; i < facs.size(); i++) {
            FacilitatorIn x = facs.get(i);
            if (x == null || x.centreId() == null || centres.findById(x.centreId()).isEmpty()) { f.put("facilitators[" + i + "].centreId", "Choose a study centre."); continue; }
            if (!seenCentres.add(x.centreId())) f.put("facilitators[" + i + "].centreId", "One facilitator per centre: this centre is listed twice.");
            check(staff, x.userId(), StaffAssignment.Kind.FACILITATOR, "facilitators[" + i + "].userId", f);
        }
        StructureService.fail(f);

        List<StaffAssignment> before = assignments.findBySessionIdAndCourseId(s.getId(), courseId);
        Set<String> old = before.stream().map(StaffingService::key).collect(Collectors.toSet());
        assignments.deleteAll(before);
        assignments.flush();
        List<StaffAssignment> now = new ArrayList<>();
        if (in.lecturerId() != null) now.add(assignment(s, StaffAssignment.Kind.LECTURER, courseId, null, in.lecturerId(), actor));
        if (in.examOfficerId() != null) now.add(assignment(s, StaffAssignment.Kind.EXAM_OFFICER, courseId, null, in.examOfficerId(), actor));
        for (FacilitatorIn x : facs) if (x.userId() != null) now.add(assignment(s, StaffAssignment.Kind.FACILITATOR, courseId, x.centreId(), x.userId(), actor));
        assignments.saveAllAndFlush(now);

        String course = c.getCode() + " · " + c.getTitle();
        List<String> changes = now.stream().map(a -> LABEL.get(a.getKind()) + ": " + staff.get(a.getUserId()).fullName()).toList();
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "STAFFING_COURSE_SET", "course", courseId,
                "Staffing for " + course + " in " + s.getName() + ": " + (changes.isEmpty() ? "nobody assigned" : String.join(", ", changes)), null, null));
        if (!Boolean.FALSE.equals(in.sendEmail())) {
            Map<UUID, String> centreNames = centres.findAll().stream().collect(Collectors.toMap(Centre::getId, Centre::getName));
            for (StaffAssignment a : now) {
                if (old.contains(key(a))) continue;
                String what = LABEL.get(a.getKind()) + " for " + course + (a.getCentreId() == null ? "" : " at " + centreNames.get(a.getCentreId()));
                notifications.assigned(staff.get(a.getUserId()), what, s.getName());
            }
        }
        Map<UUID, Centre> centreById = centres.findAll().stream().collect(Collectors.toMap(Centre::getId, x -> x));
        return courseRow(c, now, staff, centreById);
    }

    /** Lecturer only, from the course form (W-13b "Lecturer (optional)"). */
    @Transactional
    public void setLecturer(UUID courseId, UUID lecturerId, AuditService.Actor actor) {
        AcademicSession s = sessions.resolve(null);
        List<StaffAssignment> current = assignments.findBySessionIdAndCourseId(s.getId(), courseId);
        List<FacilitatorIn> facs = current.stream().filter(a -> a.getKind() == StaffAssignment.Kind.FACILITATOR).map(a -> new FacilitatorIn(a.getCentreId(), a.getUserId())).toList();
        UUID exam = current.stream().filter(a -> a.getKind() == StaffAssignment.Kind.EXAM_OFFICER).map(StaffAssignment::getUserId).findFirst().orElse(null);
        setCourse(courseId, new CourseStaffIn(s.getId(), lecturerId, facs, exam, true), actor);
    }

    @Transactional
    public CentreRow setCentre(UUID centreId, CentreStaffIn in, AuditService.Actor actor) {
        Centre c = centres.findById(centreId).orElseThrow(ApiException::notFound);
        AcademicSession s = sessions.resolve(in.sessionId());
        Map<UUID, Staff> staff = identity.staffById();
        Map<String, String> f = new LinkedHashMap<>();
        check(staff, in.coordinatorId(), StaffAssignment.Kind.COORDINATOR, "coordinatorId", f);
        StructureService.fail(f);
        List<StaffAssignment> before = assignments.findBySessionIdAndCentreIdAndKind(s.getId(), centreId, StaffAssignment.Kind.COORDINATOR);
        boolean same = before.stream().anyMatch(a -> a.getUserId().equals(in.coordinatorId()));
        assignments.deleteAll(before);
        assignments.flush();
        if (in.coordinatorId() != null) assignments.saveAndFlush(assignment(s, StaffAssignment.Kind.COORDINATOR, null, centreId, in.coordinatorId(), actor));
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "STAFFING_CENTRE_SET", "centre", centreId,
                in.coordinatorId() == null ? "Removed the coordinator of " + c.getName() + " for " + s.getName()
                        : staff.get(in.coordinatorId()).fullName() + " coordinates " + c.getName() + " in " + s.getName(), null, null));
        if (in.coordinatorId() != null && !same && !Boolean.FALSE.equals(in.sendEmail())) {
            notifications.assigned(staff.get(in.coordinatorId()), "Centre coordinator for " + c.getName(), s.getName());
        }
        Person p = in.coordinatorId() == null ? null : Person.of(in.coordinatorId(), staff);
        return new CentreRow(new CentreRef(c.getId(), c.getName(), c.getCode()), p, studentsClient.summary().atCentre(centreId), p == null);
    }

    /** W-16d: fills the empty places of a session from an earlier one; never overwrites. */
    @Transactional
    public CopyResult copy(CopyIn in, AuditService.Actor actor) {
        AcademicSession to = sessions.resolve(in.toSessionId());
        AcademicSession from;
        if (in.fromSessionId() != null) {
            from = sessionRepo.findById(in.fromSessionId()).orElseThrow(ApiException::notFound);
        } else {
            from = sessionRepo.newestFirst().stream().filter(x -> !x.getId().equals(to.getId()))
                    .filter(x -> x.getStartsOn() == null || to.getStartsOn() == null || x.getStartsOn().isBefore(to.getStartsOn()))
                    .findFirst().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "no_previous_session", "There is no earlier session to copy from."));
        }
        if (from.getId().equals(to.getId())) throw ApiException.badRequest("same_session", "Choose a different session to copy from.");
        Map<UUID, Staff> staff = identity.staffById();
        Map<UUID, Course> courseById = courses.findAll().stream().collect(Collectors.toMap(Course::getId, c -> c));
        Map<UUID, Centre> centreById = centres.findAll().stream().collect(Collectors.toMap(Centre::getId, c -> c));
        Set<String> taken = assignments.findBySessionId(to.getId()).stream().map(StaffingService::slot).collect(Collectors.toSet());
        List<StaffAssignment> add = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (StaffAssignment a : assignments.findBySessionId(from.getId())) {
            if (taken.contains(slot(a))) continue;
            String what = describe(a, courseById, centreById);
            Staff s = staff.get(a.getUserId());
            if (s == null) { skipped.add(new Skipped(what, "the person is no longer on the staff list")); continue; }
            if (s.suspended()) { skipped.add(new Skipped(what, s.fullName() + " is suspended")); continue; }
            if (!s.has(ROLE.get(a.getKind()))) { skipped.add(new Skipped(what, s.fullName() + " no longer has the " + LABEL.get(a.getKind()) + " role")); continue; }
            add.add(assignment(to, a.getKind(), a.getCourseId(), a.getCentreId(), a.getUserId(), actor));
            taken.add(slot(a));
        }
        assignments.saveAllAndFlush(add);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "STAFFING_COPIED", "session", to.getId(),
                "Copied " + add.size() + " staffing places from " + from.getName() + " to " + to.getName() + (skipped.isEmpty() ? "" : " (" + skipped.size() + " skipped)"), null, null));
        return new CopyResult(add.size(), skipped, from.getName(), to.getName());
    }

    /** People who may be picked for a role, those scoped to the department first. */
    private static final Set<String> PICKABLE = Set.of("LECTURER", "FACILITATOR", "EXAM_OFFICER", "CENTRE_COORDINATOR");

    public List<Option> options(String role, UUID departmentId) {
        String r = pickable(role);
        return build(identity.staff(r), r, departmentId);
    }

    /** Several roles from one call to identity-service: a staffing dialog loads its lists once (`roles=LECTURER,FACILITATOR`). */
    public Map<String, List<Option>> optionsByRole(String roles, UUID departmentId) {
        List<String> rs = Arrays.stream(roles == null ? new String[0] : roles.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(this::pickable).distinct().toList();
        if (rs.isEmpty()) pickable(null);
        List<Staff> all = identity.staff(null);
        Map<String, List<Option>> out = new LinkedHashMap<>();
        for (String r : rs) out.put(r, build(all, r, departmentId));
        return out;
    }

    private String pickable(String role) {
        String r = role == null ? null : role.toUpperCase(Locale.ROOT);
        if (r == null || !PICKABLE.contains(r)) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Choose a role.", Map.of("role", "Use LECTURER, FACILITATOR, EXAM_OFFICER or CENTRE_COORDINATOR."));
        }
        return r;
    }

    private static List<Option> build(List<Staff> staff, String r, UUID departmentId) {
        return staff.stream().filter(s -> !s.suspended())
                .map(s -> Map.entry(s, s.roles().stream().filter(x -> x.role().equals(r)).findFirst().orElse(null)))
                .filter(e -> e.getValue() != null)
                .map(e -> {
                    Staff s = e.getKey(); RoleView rv = e.getValue();
                    boolean here = departmentId != null && departmentId.equals(rv.scopeId());
                    return Map.entry(here ? 0 : 1, new Option(s.id(), s.fullName(), s.email(), s.status(), rv.scopeLabel()));
                })
                .sorted(Comparator.<Map.Entry<Integer, Option>>comparingInt(Map.Entry::getKey).thenComparing(e -> e.getValue().fullName()))
                .map(Map.Entry::getValue).toList();
    }

    /** What a person is assigned to (W-21 suspend warning, the person's page). */
    @Transactional(readOnly = true)
    public List<UserPlace> forUser(UUID userId) {
        UUID cur = sessions.current().map(AcademicSession::getId).orElse(null);
        Map<UUID, Course> courseById = courses.findAll().stream().collect(Collectors.toMap(Course::getId, c -> c));
        Map<UUID, Centre> centreById = centres.findAll().stream().collect(Collectors.toMap(Centre::getId, c -> c));
        Map<UUID, String> sessionNames = sessionRepo.findAll().stream().collect(Collectors.toMap(AcademicSession::getId, AcademicSession::getName));
        return assignments.findByUserId(userId).stream()
                .map(a -> new UserPlace(a.getKind().name(), LABEL.get(a.getKind()) + " · " + describe(a, courseById, centreById),
                        sessionNames.get(a.getSessionId()), a.getSessionId().equals(cur)))
                .sorted(Comparator.comparing(UserPlace::current).reversed().thenComparing(UserPlace::what)).toList();
    }

    private void check(Map<UUID, Staff> staff, UUID userId, StaffAssignment.Kind kind, String field, Map<String, String> f) {
        if (userId == null) return;
        Staff s = staff.get(userId);
        if (s == null) f.put(field, "Choose someone from People & roles.");
        else if (s.suspended()) f.put(field, s.fullName() + " is suspended. Choose someone else or reinstate them first.");
        else if (!s.has(ROLE.get(kind))) f.put(field, s.fullName() + " does not have the " + LABEL.get(kind) + " role. Give it on People & roles first.");
    }

    private StaffAssignment assignment(AcademicSession s, StaffAssignment.Kind kind, UUID courseId, UUID centreId, UUID userId, AuditService.Actor actor) {
        StaffAssignment a = new StaffAssignment();
        a.setId(UUID.randomUUID());
        a.setSessionId(s.getId());
        a.setKind(kind);
        a.setCourseId(courseId);
        a.setCentreId(centreId);
        a.setUserId(userId);
        a.setCreatedAt(clock.instant());
        a.setCreatedBy(actor.userId());
        return a;
    }

    private static String key(StaffAssignment a) {
        return slot(a) + ":" + a.getUserId();
    }

    private static String slot(StaffAssignment a) {
        return a.getKind() + ":" + a.getCourseId() + ":" + (a.getKind() == StaffAssignment.Kind.FACILITATOR || a.getKind() == StaffAssignment.Kind.COORDINATOR ? a.getCentreId() : "");
    }

    private static String describe(StaffAssignment a, Map<UUID, Course> courseById, Map<UUID, Centre> centreById) {
        Course c = a.getCourseId() == null ? null : courseById.get(a.getCourseId());
        Centre ce = a.getCentreId() == null ? null : centreById.get(a.getCentreId());
        if (c == null) return ce == null ? "" : ce.getName();
        return c.getCode() + " · " + c.getTitle() + (ce == null ? "" : " · " + ce.getName());
    }

    /** "CSC 201 · Computer Science": the course code and the department of the first programme offering it. */
    @Transactional(readOnly = true)
    public String courseLabel(Course c) {
        return offers.findByCourseId(c.getId()).stream().findFirst()
                .flatMap(o -> programmes.findById(o.getProgrammeId()))
                .flatMap(p -> departments.findById(p.getDepartmentId()))
                .map(d -> c.getCode() + " · " + shortDepartment(d.getName()))
                .orElse(c.getCode() + " · " + c.getTitle());
    }

    static String shortDepartment(String name) {
        return name.replaceFirst("(?i)^department of\\s+", "");
    }
}
