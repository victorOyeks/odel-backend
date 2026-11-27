package com.odel.academic.service;

import com.odel.academic.client.IdentityClient;
import com.odel.academic.client.StudentsClient;
import com.odel.academic.dto.ChecklistDtos.Checklist;
import com.odel.academic.dto.ChecklistDtos.Step;
import com.odel.academic.dto.PeerDtos.StaffSummary;
import com.odel.academic.dto.PeerDtos.StudentsSummary;
import com.odel.academic.dto.PeerDtos.Welcome;
import com.odel.academic.dto.StaffingDtos.Gaps;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.NumberFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * W-10 Setup checklist, worked out from the real records every time it is asked for. Steps 5, 8 and 9 read numbers
 * from identity-service and the students service; if one of them does not answer the step keeps its default text.
 */
@Service
public class ChecklistService {
    static final String NOT_STARTED = "NOT_STARTED", IN_PROGRESS = "IN_PROGRESS", DONE = "DONE", LOCKED = "LOCKED";

    private final SessionService sessions;
    private final SemesterRepository semesters;
    private final FacultyRepository faculties;
    private final DepartmentRepository departments;
    private final ProgrammeRepository programmes;
    private final CourseRepository courses;
    private final CourseProgrammeRepository offers;
    private final CentreRepository centres;
    private final RoomRepository rooms;
    private final StaffingService staffing;
    private final GradingService grading;
    private final IdentityClient identity;
    private final StudentsClient students;

    public ChecklistService(SessionService sessions, SemesterRepository semesters, FacultyRepository faculties, DepartmentRepository departments,
                            ProgrammeRepository programmes, CourseRepository courses, CourseProgrammeRepository offers, CentreRepository centres,
                            RoomRepository rooms, StaffingService staffing, GradingService grading, IdentityClient identity, StudentsClient students) {
        this.sessions = sessions;
        this.semesters = semesters;
        this.faculties = faculties;
        this.departments = departments;
        this.programmes = programmes;
        this.courses = courses;
        this.offers = offers;
        this.centres = centres;
        this.rooms = rooms;
        this.staffing = staffing;
        this.grading = grading;
        this.identity = identity;
        this.students = students;
    }

    private record Def(String key, String title, String summary, String href) {}

    private static final List<Def> STEPS = List.of(
            new Def("SESSION", "Session and semesters", "Session dates and semesters", "/staff/academic-setup/sessions"),
            new Def("STRUCTURE", "Academic structure", "Faculties, departments and programmes", "/staff/academic-setup/structure"),
            new Def("CATALOGUE", "Course catalogue", "Courses for each programme", "/staff/courses"),
            new Def("CENTRES", "Study centres", "Where students study and sit exams", "/staff/centres"),
            new Def("PEOPLE", "People and roles", "Invite lecturers, coordinators and staff", "/staff/people"),
            new Def("STAFFING", "Assign staff to courses and centres", "Who teaches each course and runs each centre", "/staff/academic-setup/staffing"),
            new Def("GRADING", "Grading rules", "Default 5-point scale (30% CA / 70% exam)", "/staff/academic-setup/grading"),
            new Def("IMPORT", "Import students", "From the university portal CSV", "/staff/students/import"),
            new Def("WELCOME", "Send welcome messages", "Email with activation link", "/staff/students/welcome"));

    private record State(String status, String summary) {}

    @Transactional(readOnly = true)
    public Checklist checklist() {
        Optional<AcademicSession> session = sessions.current();
        Map<String, State> s = new HashMap<>();
        s.put("SESSION", session(session));
        s.put("STRUCTURE", structure());
        s.put("CATALOGUE", catalogue());
        long centreCount = centres.count();
        Gaps gaps = session.map(staffing::gaps).orElse(null);
        s.put("CENTRES", centres(centreCount, gaps));
        s.put("PEOPLE", people());
        s.put("STAFFING", staffingState(gaps));
        s.put("GRADING", gradingState());
        StudentsSummary st = students.summary();

        List<Step> steps = new ArrayList<>();
        int done = 0;
        for (int i = 0; i < STEPS.size(); i++) {
            Def d = STEPS.get(i);
            State state;
            String lockedReason = null;
            if (d.key().equals("IMPORT")) {
                boolean ready = DONE.equals(s.get("SESSION").status()) && DONE.equals(s.get("STRUCTURE").status()) && DONE.equals(s.get("CENTRES").status());
                if (!ready) { state = new State(LOCKED, null); lockedReason = "Finish steps 1, 2 and 4 first"; }
                else state = st.total() > 0 ? new State(DONE, count(st.total()) + (st.total() == 1 ? " student imported" : " students imported")) : new State(NOT_STARTED, null);
            } else if (d.key().equals("WELCOME")) {
                if (st.total() == 0) { state = new State(LOCKED, null); lockedReason = "Import students first"; }
                else state = welcome(st.welcomeOrZero());
            } else {
                state = s.get(d.key());
            }
            if (DONE.equals(state.status())) done++;
            String action = switch (state.status()) {
                case LOCKED -> null;
                case DONE -> "Edit";
                case IN_PROGRESS -> "Continue";
                default -> d.key().equals("GRADING") ? "Review" : "Start";
            };
            steps.add(new Step(i + 1, d.key(), d.title(), state.summary() == null ? d.summary() : state.summary(), state.status(), action, d.href(), lockedReason));
        }
        return new Checklist(session.map(AcademicSession::getName).orElse(null), steps, done, STEPS.size(), Math.round(done * 100f / STEPS.size()));
    }

    private State session(Optional<AcademicSession> session) {
        if (session.isEmpty()) return new State(NOT_STARTED, null);
        List<Semester> sems = semesters.findBySessionIdOrderByNumber(session.get().getId());
        long dated = sems.stream().filter(x -> x.getStartsOn() != null && x.getEndsOn() != null).count();
        List<String> parts = new ArrayList<>(List.of(session.get().getName(), dated + (dated == 1 ? " semester" : " semesters")));
        String reg = SessionService.shortRegistration(sems);
        parts.add(reg == null ? "registration not set" : reg);
        return new State(SessionService.complete(sems) ? DONE : IN_PROGRESS, String.join(" · ", parts));
    }

    private State structure() {
        List<Faculty> f = faculties.findAll();
        if (f.isEmpty()) return new State(NOT_STARTED, null);
        List<Department> d = departments.findAll();
        List<Programme> p = programmes.findAll();
        Set<UUID> facultiesWithDept = d.stream().map(Department::getFacultyId).collect(Collectors.toSet());
        Set<UUID> deptsWithProg = p.stream().map(Programme::getDepartmentId).collect(Collectors.toSet());
        boolean complete = !p.isEmpty() && f.stream().allMatch(x -> facultiesWithDept.contains(x.getId())) && d.stream().allMatch(x -> deptsWithProg.contains(x.getId()));
        return new State(complete ? DONE : IN_PROGRESS, plural(f.size(), "faculty", "faculties") + " · " + plural(d.size(), "department", "departments")
                + " · " + plural(p.size(), "programme", "programmes"));
    }

    private State catalogue() {
        List<Course> c = courses.findAll();
        if (c.isEmpty()) return new State(NOT_STARTED, null);
        List<CourseProgramme> o = offers.findAll();
        Set<UUID> offered = o.stream().map(CourseProgramme::getCourseId).collect(Collectors.toSet());
        Set<UUID> programmesWithCourses = o.stream().map(CourseProgramme::getProgrammeId).collect(Collectors.toSet());
        long notOffered = c.stream().filter(x -> !offered.contains(x.getId())).count();
        long emptyProgrammes = programmes.findAll().stream().filter(p -> !programmesWithCourses.contains(p.getId())).count();
        String summary = plural(c.size(), "course", "courses");
        if (notOffered > 0) summary += " · " + count(notOffered) + " not offered to any programme";
        if (emptyProgrammes > 0) summary += " · " + plural(emptyProgrammes, "programme", "programmes") + " without courses";
        return new State(notOffered == 0 && emptyProgrammes == 0 ? DONE : IN_PROGRESS, summary);
    }

    private State centres(long count, Gaps gaps) {
        if (count == 0) return new State(NOT_STARTED, null);
        Set<UUID> withRooms = rooms.findAll().stream().map(Room::getCentreId).collect(Collectors.toSet());
        long noRooms = centres.findAll().stream().filter(c -> !withRooms.contains(c.getId())).count();
        String summary = plural(count, "centre", "centres");
        if (noRooms > 0) summary += " · " + count(noRooms) + " without rooms";
        long noCoord = gaps == null ? count : gaps.needCoordinator();
        if (noCoord > 0) summary += " · " + count(noCoord) + " without a coordinator";
        return new State(noRooms == 0 ? DONE : IN_PROGRESS, summary);
    }

    private State people() {
        Optional<StaffSummary> s = identity.staffSummary();
        if (s.isEmpty()) return new State(NOT_STARTED, null);
        Map<String, Long> by = s.get().byRole() == null ? Map.of() : s.get().byRole();
        long admins = by.getOrDefault("ODEL_ADMIN", 0L) + by.getOrDefault("ODEL_DIRECTOR", 0L);
        long others = by.entrySet().stream().filter(e -> !e.getKey().equals("ODEL_ADMIN") && !e.getKey().equals("ODEL_DIRECTOR")).mapToLong(Map.Entry::getValue).sum();
        if (others == 0 && s.get().total() <= Math.max(admins, 1)) return new State(NOT_STARTED, null);
        String summary = count(s.get().total()) + " staff invited · " + count(s.get().active()) + " active";
        return new State(by.getOrDefault("LECTURER", 0L) > 0 ? DONE : IN_PROGRESS, summary);
    }

    private State staffingState(Gaps g) {
        if (g == null || !g.anyAssigned()) {
            return new State(NOT_STARTED, g == null || g.courses() == 0 ? null : StaffingService.summary(g.courses(), g.needLecturer(), g.centres(), g.needCoordinator()));
        }
        boolean complete = g.needLecturer() == 0 && g.needCoordinator() == 0 && g.courses() > 0;
        return new State(complete ? DONE : IN_PROGRESS, StaffingService.summary(g.courses(), g.needLecturer(), g.centres(), g.needCoordinator()));
    }

    private State gradingState() {
        var r = grading.defaultRules();
        String summary = Grading.summary(r, false);
        long own = grading.programmesWithOwnRules();
        if (own > 0) summary += " · " + plural(own, "programme has", "programmes have") + " its own rules".replace(" its", own == 1 ? " its" : " their");
        return new State(grading.defaultSaved() ? DONE : NOT_STARTED, summary);
    }

    private static State welcome(Welcome w) {
        if (w.sent() == 0 && w.failed() == 0) return new State(NOT_STARTED, count(w.notSent()) + " not sent yet");
        List<String> parts = new ArrayList<>(List.of(count(w.sent()) + " sent"));
        if (w.failed() > 0) parts.add(count(w.failed()) + " failed");
        if (w.notSent() > 0) parts.add(count(w.notSent()) + " not sent");
        return new State(w.notSent() == 0 ? DONE : IN_PROGRESS, String.join(" · ", parts));
    }

    static String count(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    static String plural(long n, String one, String many) {
        return count(n) + " " + (n == 1 ? one : many);
    }
}
