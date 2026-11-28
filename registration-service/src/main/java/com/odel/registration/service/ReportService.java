package com.odel.registration.service;

import com.odel.common.ApiException;
import com.odel.common.files.Csv;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.registration.client.StudentsClient;
import com.odel.registration.dto.PeerDtos.StudentRef;
import com.odel.registration.dto.RegistrationDtos.Ref;
import com.odel.registration.dto.StaffDtos.*;
import com.odel.registration.entity.*;
import com.odel.registration.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A-07 Registrations: every student (or the coordinator's centres) with their state for the semester, the cards, the CSV
 * export; A-07b one student's registration; the staff copy of a slip.
 */
@Service
public class ReportService {
    private final StudentsClient students;
    private final CalendarService calendar;
    private final CoordinatorDirectory coordinators;
    private final FeeService fees;
    private final RegistrationService registration;
    private final RequestService requestService;
    private final RegistrationRepository registrations;
    private final RegistrationCourseRepository courses;
    private final RequestRepository requests;
    private final CarryOverRepository carryOvers;
    private final TransactionTemplate tx;

    public ReportService(StudentsClient students, CalendarService calendar, CoordinatorDirectory coordinators, FeeService fees,
                         RegistrationService registration, RequestService requestService, RegistrationRepository registrations,
                         RegistrationCourseRepository courses, RequestRepository requests, CarryOverRepository carryOvers, TransactionTemplate tx) {
        this.students = students;
        this.calendar = calendar;
        this.coordinators = coordinators;
        this.fees = fees;
        this.registration = registration;
        this.requestService = requestService;
        this.registrations = registrations;
        this.courses = courses;
        this.requests = requests;
        this.carryOvers = carryOvers;
        this.tx = tx;
    }

    static void staff(CurrentUser cu) {
        Access.require(cu, Role.ODEL_ADMIN, Role.ODEL_DIRECTOR, Role.CENTRE_COORDINATOR);
    }

    record Built(Report report, List<ReportRow> all, Map<UUID, List<RegistrationCourse>> coursesByReg) {}

    public Report report(CurrentUser cu, UUID semesterId, UUID centreId, UUID programmeId, Integer level, String status, String q, int page, int size) {
        return build(cu, semesterId, centreId, programmeId, level, status, q, page, size, false).report();
    }

    private Built build(CurrentUser cu, UUID semesterId, UUID centreId, UUID programmeId, Integer level, String status, String q, int page, int size,
                        boolean withCourses) {
        staff(cu);
        CalendarService.Choice c = calendar.pick(semesterId);
        Map<UUID, UUID> coord = coordinators.byCentre();
        Set<UUID> visible = ApprovalScope.visibleCentres(cu.activeRole(), cu.userId(), coord);
        List<StudentRef> roster = students.roster().stream().filter(s -> visible == null || (s.centreId() != null && visible.contains(s.centreId()))).toList();
        UUID sem = c.semesterId();
        List<Registration> regs = sem == null ? List.of() : tx.execute(t -> registrations.forSemester(sem));
        Map<UUID, Registration> regByStudent = regs.stream().collect(Collectors.toMap(Registration::getStudentId, r -> r, (a, b) -> a));
        List<RegistrationRequest> reqs = sem == null ? List.of() : tx.execute(t -> requests.forSemester(sem));
        Set<UUID> lateStudents = reqs.stream().filter(r -> r.getKind() == RegistrationRequest.Kind.LATE && r.getStatus() == RegistrationRequest.Status.PENDING)
                .map(RegistrationRequest::getStudentId).collect(Collectors.toSet());
        Map<UUID, String> fee = sem == null ? Map.of() : fees.statuses(sem, roster.stream().map(StudentRef::id).toList());
        boolean feeKnown = !fee.isEmpty() || roster.isEmpty();

        List<ReportRow> rows = new ArrayList<>();
        for (StudentRef s : roster) {
            Registration r = regByStudent.get(s.id());
            String st, label;
            String f = fee.get(s.id());
            if (r != null) { st = "REGISTERED"; label = "Registered"; }
            else if (lateStudents.contains(s.id())) { st = "LATE_REQUEST"; label = "Late request"; }
            else if (!feeKnown || f == null) { st = "NOT_REGISTERED"; label = "Not registered"; }
            else if (f.equals("PAID")) { st = "PAID_NOT_REGISTERED"; label = "Paid, not registered"; }
            else if (f.equals("CONFIRMING")) { st = "FEE_CONFIRMING"; label = "Fee confirming"; }
            else { st = "NOT_PAID"; label = "Not paid"; }
            rows.add(new ReportRow(s.id(), s.fullName(), s.matricNumber(), new Ref(s.programmeId(), s.programmeCode(), s.programmeName()), s.level(),
                    new Ref(s.centreId(), s.centreCode(), s.centreName()), r == null ? null : r.getCourseCount(), r == null ? null : r.getTotalUnits(),
                    r == null ? null : r.getSubmittedAt(), r == null ? null : r.getChangedAt(), r == null ? null : r.getId(), st, label, f));
        }
        Set<UUID> rosterIds = roster.stream().map(StudentRef::id).collect(Collectors.toSet());
        long registered = regs.stream().filter(r -> rosterIds.contains(r.getStudentId())).count();
        Long paid = feeKnown ? fee.values().stream().filter("PAID"::equals).count() : null;
        Long paidNotReg = feeKnown ? rows.stream().filter(r -> r.status().equals("PAID_NOT_REGISTERED")).count() : null;
        List<RegistrationRequest> pendingLate = reqs.stream().filter(r -> rosterIds.contains(r.getStudentId()) && r.getKind() == RegistrationRequest.Kind.LATE
                && r.getStatus() == RegistrationRequest.Status.PENDING).toList();
        long atCentres = pendingLate.stream().filter(r -> r.getCentreId() != null && coord.containsKey(r.getCentreId())).count();
        long changes = reqs.stream().filter(r -> rosterIds.contains(r.getStudentId()) && r.getKind() == RegistrationRequest.Kind.CHANGE
                && r.getStatus() == RegistrationRequest.Status.PENDING).count();
        Cards cards = new Cards(registered, paid, paidNotReg, pendingLate.size(), atCentres, pendingLate.size() - atCentres, changes);

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String matricNeedle = needle.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        List<ReportRow> filtered = rows.stream()
                .filter(r -> centreId == null || centreId.equals(r.centre().id()))
                .filter(r -> programmeId == null || programmeId.equals(r.programme().id()))
                .filter(r -> level == null || level == r.level())
                .filter(r -> status == null || status.isBlank() || status.equalsIgnoreCase(r.status()))
                .filter(r -> needle.isEmpty() || r.fullName().toLowerCase(Locale.ROOT).contains(needle) || r.matricNumber().contains(matricNeedle))
                .sorted(Comparator.comparing((ReportRow r) -> r.status().equals("REGISTERED") ? 0 : 1).thenComparing(ReportRow::fullName))
                .toList();
        int sz = Math.max(1, Math.min(size, 200)), p = Math.max(0, page);
        Window w = c.window();
        String subtitle = c.none() ? "Registration is not set up yet"
                : CalendarService.label(c.semester().sessionName(), c.semester().number())
                + (w.opensOn() == null ? "" : " · open " + Dates.range(w.opensOn(), w.closesOn()))
                + (w.lateClosesOn() == null ? "" : " · late registration until " + Dates.day(w.lateClosesOn()));
        Report report = new Report(CalendarService.view(c.semester()), CalendarService.view(w, null), subtitle, cards,
                filtered.stream().skip((long) p * sz).limit(sz).toList(), p, sz, filtered.size(), feeKnown);
        Map<UUID, List<RegistrationCourse>> byReg = Map.of();
        if (withCourses) {
            Set<UUID> ids = filtered.stream().map(ReportRow::registrationId).filter(Objects::nonNull).collect(Collectors.toSet());
            byReg = ids.isEmpty() ? Map.of() : tx.execute(t -> courses.forRegistrations(ids)).stream()
                    .collect(Collectors.groupingBy(RegistrationCourse::getRegistrationId));
        }
        return new Built(report, filtered, byReg);
    }

    public String csv(CurrentUser cu, UUID semesterId, UUID centreId, UUID programmeId, Integer level, String status, String q) {
        Built b = build(cu, semesterId, centreId, programmeId, level, status, q, 0, 1, true);
        StringBuilder sb = new StringBuilder("matric_number,full_name,programme,level,centre,status,courses,units,submitted_at,course_codes\n");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(Dates.LAGOS);
        for (ReportRow r : b.all()) {
            List<RegistrationCourse> cs = r.registrationId() == null ? List.of() : Views.ordered(b.coursesByReg().getOrDefault(r.registrationId(), List.of()));
            sb.append(Csv.line(Arrays.asList(r.matricNumber(), r.fullName(), r.programme().name(), String.valueOf(r.level()), r.centre().name(), r.statusLabel(),
                    r.courseCount() == null ? "" : r.courseCount().toString(), r.units() == null ? "" : r.units().toString(),
                    r.submittedAt() == null ? "" : f.format(r.submittedAt()), String.join(" ", cs.stream().map(RegistrationCourse::getCode).toList())))).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- A-07b

    public StudentRegistration student(CurrentUser cu, UUID studentId, UUID semesterId) {
        staff(cu);
        StudentRef s = students.byId(studentId);
        if (!ApprovalScope.canSee(cu.activeRole(), cu.userId(), s.centreId(), coordinators.byCentre())) throw ApiException.notFound();
        CalendarService.Choice c = calendar.pick(semesterId);
        Registration reg = c.none() ? null : tx.execute(t -> registrations.forStudent(studentId, c.semesterId()).orElse(null));
        var view = reg == null ? null : registration.view(reg, c.today(), true);
        var reqs = tx.execute(t -> c.none() ? requests.allForStudent(studentId) : requests.forStudent(studentId, c.semesterId()));
        var requestViews = reqs.stream().map(requestService::view).toList();
        var carry = tx.execute(t -> carryOvers.forStudent(studentId)).stream().map(Views::carryOver).toList();
        return new StudentRegistration(RegistrationService.studentView(s), view, requestViews, carry);
    }

    public RegistrationService.Slip slip(CurrentUser cu, UUID registrationId) {
        staff(cu);
        Registration r = tx.execute(t -> registrations.findInTenant(registrationId).orElseThrow(ApiException::notFound));
        if (!ApprovalScope.canSee(cu.activeRole(), cu.userId(), r.getCentreId(), coordinators.byCentre())) throw ApiException.notFound();
        return registration.slip(r);
    }
}
