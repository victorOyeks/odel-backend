package com.odel.registration.service;

import com.odel.common.ApiException;
import com.odel.common.Tokens;
import com.odel.common.audit.AuditService;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.registration.client.AcademicClient;
import com.odel.registration.client.NotificationClient;
import com.odel.registration.client.StudentsClient;
import com.odel.registration.client.TenantClient;
import com.odel.registration.dto.PeerDtos;
import com.odel.registration.dto.PeerDtos.StudentRef;
import com.odel.registration.dto.RegistrationDtos.*;
import com.odel.registration.dto.RequestDtos;
import com.odel.registration.entity.*;
import com.odel.registration.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** The student's registration (R-01 … R-05b): the window, the fee block, choosing courses, submitting, the slip. */
@Service
public class RegistrationService {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final StudentsClient students;
    private final AcademicClient academic;
    private final TenantClient tenants;
    private final NotificationClient notifications;
    private final CalendarService calendar;
    private final RulesService rules;
    private final FeeService fees;
    private final CoordinatorDirectory coordinators;
    private final RegistrationRepository registrations;
    private final RegistrationCourseRepository courses;
    private final RegistrationEventRepository events;
    private final CarryOverRepository carryOvers;
    private final RequestRepository requests;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SecureRandom random = Tokens.secureRandom();

    public RegistrationService(StudentsClient students, AcademicClient academic, TenantClient tenants, NotificationClient notifications,
                               CalendarService calendar, RulesService rules, FeeService fees, CoordinatorDirectory coordinators,
                               RegistrationRepository registrations, RegistrationCourseRepository courses, RegistrationEventRepository events,
                               CarryOverRepository carryOvers, RequestRepository requests, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.students = students;
        this.academic = academic;
        this.tenants = tenants;
        this.notifications = notifications;
        this.calendar = calendar;
        this.rules = rules;
        this.fees = fees;
        this.coordinators = coordinators;
        this.registrations = registrations;
        this.courses = courses;
        this.events = events;
        this.carryOvers = carryOvers;
        this.requests = requests;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- who and when

    /** The signed-in student's record (student-service). Students only. */
    public StudentRef me(CurrentUser cu) {
        Access.require(cu, Role.STUDENT);
        try {
            return students.byUser(cu.userId());
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) throw new ApiException(HttpStatus.NOT_FOUND, "no_student_record",
                    "We could not find your student record. Contact your study centre.");
            throw e;
        }
    }

    /** What the student may do now, and why not (the gate shared by the overview and submit). */
    record Gate(boolean canRegister, Blocked blocked, LocalDate registerBy, boolean late) {}

    Gate gate(StudentRef s, CalendarService.Choice c, FeeView fee, Registration existing, List<RegistrationRequest> reqs) {
        LocalDate today = c.today();
        Window w = c.window();
        if ("SUSPENDED".equals(s.status())) return blocked("suspended", "Your account is suspended. Contact the ODeL office.");
        if (existing != null) return blocked("already_registered", "You have already registered for this semester. Ask for a course change instead.");
        LocalDate registerBy = null;
        boolean late = false;
        switch (w.state()) {
            case NOT_SET -> { return blocked("not_set", "Registration for this semester is not set up yet. Check back later."); }
            case NOT_OPEN -> { return blocked("not_open", "Registration opens " + Dates.full(w.opensOn()) + "."); }
            case OPEN -> registerBy = w.closesOn();
            case LATE, CLOSED -> {
                Optional<RegistrationRequest> approved = reqs.stream().filter(r -> r.getKind() == RegistrationRequest.Kind.LATE
                        && r.getStatus() == RegistrationRequest.Status.APPROVED && r.getRegisterBy() != null && !today.isAfter(r.getRegisterBy())).findFirst();
                if (approved.isPresent()) {
                    registerBy = approved.get().getRegisterBy();
                    late = true;
                } else if (w.state() == Window.State.LATE) {
                    boolean pending = reqs.stream().anyMatch(r -> r.getKind() == RegistrationRequest.Kind.LATE && r.getStatus() == RegistrationRequest.Status.PENDING);
                    if (pending) return blocked("late_request_pending", "Your late registration request is waiting for your centre coordinator. The decision will come by email.");
                    return blocked("late_request_needed", "Registration closed on " + Dates.weekday(w.closesOn()) + ". Ask your centre for late registration by "
                            + Dates.weekday(w.lateClosesOn()) + ".");
                } else {
                    return blocked("closed", "Registration closed on " + Dates.full(w.lateClosesOn() != null ? w.lateClosesOn() : w.closesOn())
                            + ". Contact your study centre.");
                }
            }
        }
        if (fee.required() && !fee.paid()) {
            return switch (fee.status()) {
                case "CONFIRMING" -> new Gate(false, new Blocked("fee_confirming",
                        "We are confirming your payment. Do not pay again: registration opens as soon as it is confirmed."), registerBy, late);
                case "UNKNOWN" -> new Gate(false, new Blocked("fee_unknown", "We could not check your fee just now. Try again in a minute."), registerBy, late);
                default -> new Gate(false, new Blocked("fee_not_paid", "Pay the ₦15,000 platform fee for " + fee.label().replace("Platform fee · ", "")
                        + " first. Registration opens as soon as it is confirmed."), registerBy, late);
            };
        }
        return new Gate(true, null, registerBy, late);
    }

    private static Gate blocked(String code, String message) {
        return new Gate(false, new Blocked(code, message), null, false);
    }

    // ---------------------------------------------------------------- R-01, R-08, Home

    public Overview overview(CurrentUser cu, UUID semesterId) {
        StudentRef s = me(cu);
        CalendarService.Choice c = calendar.pick(semesterId);
        StudentView sv = studentView(s);
        if (c.none()) {
            return new Overview(sv, null, CalendarService.view(c.window(), null), null, false,
                    new Blocked("not_set", "Registration for this semester is not set up yet. Check back later."), null, null, null, limits(s));
        }
        String label = CalendarService.label(c.semester().sessionName(), c.semester().number());
        FeeView fee = fees.fee(s.id(), c.semesterId(), label);
        Registration existing = tx.execute(t -> registrations.forStudent(s.id(), c.semesterId()).orElse(null));
        List<RegistrationRequest> reqs = tx.execute(t -> requests.forStudent(s.id(), c.semesterId()));
        Gate g = gate(s, c, fee, existing, reqs);
        RegistrationView reg = existing == null ? null : view(existing, c, false);
        Map<UUID, UUID> coord = coordinators.byCentre();
        RequestDtos.RequestView late = reqs.stream().filter(r -> r.getKind() == RegistrationRequest.Kind.LATE).findFirst()
                .map(r -> Views.request(r, coord.containsKey(r.getCentreId()), null, null)).orElse(null);
        RequestDtos.RequestView change = reqs.stream().filter(r -> r.getKind() == RegistrationRequest.Kind.CHANGE).findFirst()
                .map(r -> Views.request(r, coord.containsKey(r.getCentreId()), limits(s).maxUnits(), existing == null ? null : Dates.lagos(existing.getSubmittedAt())))
                .orElse(null);
        return new Overview(sv, CalendarService.view(c.semester()), CalendarService.view(c.window(), g.registerBy()), fee, g.canRegister(), g.blocked(),
                reg, late, change, limits(s));
    }

    // ---------------------------------------------------------------- R-02, R-09b

    public CourseChoice courseChoice(CurrentUser cu, UUID semesterId) {
        StudentRef s = me(cu);
        CalendarService.Choice c = calendar.pick(semesterId);
        if (c.none()) throw new ApiException(HttpStatus.CONFLICT, "not_set", "Registration for this semester is not set up yet. Check back later.");
        Offer o = offer(s, c);
        Registration existing = tx.execute(t -> registrations.forStudent(s.id(), c.semesterId()).orElse(null));
        Set<UUID> chosen = existing == null ? Set.of() : new HashSet<>(tx.execute(t -> courses.forRegistration(existing.getId())).stream()
                .map(RegistrationCourse::getCourseId).toList());
        List<CourseOption> compulsory = o.offered().stream().filter(x -> x.kind() == CourseRules.Kind.COMPULSORY && !o.carryIds().contains(x.id()))
                .map(x -> new CourseOption(x.id(), x.code(), x.title(), x.units(), "COMPULSORY", true, true, null)).toList();
        List<CourseOption> carry = o.carry().stream().map(x -> new CourseOption(x.id(), x.code(), x.title(), x.units(), "CARRY_OVER", true, true,
                o.fromSession().get(x.id()))).toList();
        List<CourseOption> electives = o.offered().stream().filter(x -> x.kind() == CourseRules.Kind.ELECTIVE && !o.carryIds().contains(x.id()))
                .map(x -> new CourseOption(x.id(), x.code(), x.title(), x.units(), "ELECTIVE", false, chosen.contains(x.id()), null)).toList();
        int lockedUnits = compulsory.stream().mapToInt(CourseOption::units).sum() + carry.stream().mapToInt(CourseOption::units).sum();
        Window w = c.window();
        String closes = switch (w.state()) {
            case OPEN -> " · closes " + Dates.weekday(w.closesOn());
            case LATE -> " · late registration until " + Dates.weekday(w.lateClosesOn());
            default -> "";
        };
        return new CourseChoice(CalendarService.view(c.semester()), CalendarService.view(w, null), limits(s), compulsory, carry, electives, lockedUnits,
                compulsory.size() + carry.size(), s.programmeName() + " · " + s.level() + "L" + closes);
    }

    record Offer(List<CourseRules.Course> offered, List<CourseRules.Course> carry, Set<UUID> carryIds, Map<UUID, String> fromSession,
                 Map<UUID, UUID> carryOverIds) {}

    Offer offer(StudentRef s, CalendarService.Choice c) {
        List<CourseRules.Course> offered = s.programmeId() == null ? List.of() : academic.offerings(s.programmeId(), s.level(), c.semester().number()).stream()
                .map(x -> new CourseRules.Course(x.id(), x.code(), x.title(), x.units(),
                        "ELECTIVE".equals(x.kind()) ? CourseRules.Kind.ELECTIVE : CourseRules.Kind.COMPULSORY)).toList();
        List<CarryOver> open = tx.execute(t -> carryOvers.openForStudent(s.id())).stream().filter(x -> x.getSemester() == c.semester().number()).toList();
        List<CourseRules.Course> carry = open.stream().map(x -> new CourseRules.Course(x.getCourseId(), x.getCourseCode(), x.getCourseTitle(), x.getUnits(),
                CourseRules.Kind.CARRY_OVER)).toList();
        Map<UUID, String> from = new HashMap<>();
        Map<UUID, UUID> ids = new HashMap<>();
        open.forEach(x -> { if (x.getFromSession() != null) from.put(x.getCourseId(), x.getFromSession()); ids.put(x.getCourseId(), x.getId()); });
        return new Offer(offered, carry, new HashSet<>(ids.keySet()), from, ids);
    }

    public Limits limits(StudentRef s) {
        CourseRules.Limits l = rules.limits(s.programmeId(), s.level());
        return new Limits(l.minUnits(), l.maxUnits(), l.maxElectives());
    }

    // ---------------------------------------------------------------- R-03, R-03b

    public CheckResult check(CurrentUser cu, CheckRequest in) {
        StudentRef s = me(cu);
        CalendarService.Choice c = calendar.pick(in.semesterId());
        if (c.none()) throw new ApiException(HttpStatus.CONFLICT, "not_set", "Registration for this semester is not set up yet. Check back later.");
        CourseRules.Limits l = rules.limits(s.programmeId(), s.level());
        Offer o = offer(s, c);
        CourseRules.Result r = CourseRules.check(o.offered(), o.carry(), in.electiveIds(), l);
        return new CheckResult(r.totalUnits(), r.courses().size(), r.electiveCount(), r.ok(), r.problems(),
                r.totalUnits() + " · min " + l.minUnits() + " · max " + l.maxUnits());
    }

    // ---------------------------------------------------------------- R-04 → R-05b

    public RegistrationView submit(CurrentUser cu, SubmitRequest in) {
        StudentRef s = me(cu);
        if (!Boolean.TRUE.equals(in.confirm())) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "confirm_required", "Tick the box to confirm these are your courses.",
                    Map.of("confirm", "Tick the box to confirm these are your courses."));
        }
        CalendarService.Choice c = calendar.pick(in.semesterId());
        if (c.none()) throw new ApiException(HttpStatus.CONFLICT, "not_set", "Registration for this semester is not set up yet. Check back later.");
        String label = CalendarService.label(c.semester().sessionName(), c.semester().number());
        FeeView fee = fees.fee(s.id(), c.semesterId(), label);
        Registration existing = tx.execute(t -> registrations.forStudent(s.id(), c.semesterId()).orElse(null));
        List<RegistrationRequest> reqs = tx.execute(t -> requests.forStudent(s.id(), c.semesterId()));
        Gate g = gate(s, c, fee, existing, reqs);
        if (!g.canRegister()) {
            String code = g.blocked().code();
            HttpStatus st = switch (code) {
                case "fee_not_paid" -> HttpStatus.PAYMENT_REQUIRED;
                case "fee_unknown" -> HttpStatus.SERVICE_UNAVAILABLE;
                case "suspended" -> HttpStatus.FORBIDDEN;
                default -> HttpStatus.CONFLICT;
            };
            throw new ApiException(st, code, g.blocked().message(), null, code.equals("fee_not_paid") ? Map.of("payPath", "/payments") : null);
        }
        CourseRules.Limits l = rules.limits(s.programmeId(), s.level());
        Offer o = offer(s, c);
        CourseRules.Result r = CourseRules.check(o.offered(), o.carry(), in.electiveIds(), l);
        if (!r.ok()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid", r.problems().get(0).message(), null, Map.of("problems", r.problems()));
        }
        if (r.courses().isEmpty()) throw ApiException.badRequest("invalid", "There are no courses for your programme and level this semester yet. Contact your study centre.");

        UUID tenant = TenantContext.require();
        Instant now = clock.instant();
        Registration reg = new Registration();
        reg.setId(UUID.randomUUID());
        reg.setStudentId(s.id());
        reg.setUserId(s.userId());
        reg.setMatricNumber(s.matricNumber());
        reg.setStudentName(s.fullName());
        reg.setSemesterId(c.semesterId());
        reg.setSessionId(c.semester().sessionId());
        reg.setSessionName(c.semester().sessionName());
        reg.setSemesterNumber(c.semester().number());
        reg.setProgrammeId(s.programmeId());
        reg.setProgrammeCode(s.programmeCode());
        reg.setProgrammeName(s.programmeName());
        reg.setLevel(s.level());
        reg.setCentreId(s.centreId());
        reg.setCentreCode(s.centreCode());
        reg.setCentreName(s.centreName());
        reg.setStatus(Registration.Status.REGISTERED);
        reg.setLate(g.late());
        reg.setTotalUnits(r.totalUnits());
        reg.setCourseCount(r.courses().size());
        reg.setSlipVersion(1);
        reg.setSubmittedAt(now);
        reg.setUpdatedAt(now);
        AuditService.Actor actor = new AuditService.Actor(cu.userId(), cu.tenantId(), s.fullName(), "STUDENT");
        try {
            tx.executeWithoutResult(t -> {
                reg.setSlipNumber(slipNumber(c.semester().sessionName(), c.semester().number(), s.matricNumber()));
                reg.setVerificationCode(verificationCode());
                registrations.save(reg);
                for (CourseRules.Course x : r.courses()) {
                    RegistrationCourse rc = new RegistrationCourse();
                    rc.setId(UUID.randomUUID());
                    rc.setRegistrationId(reg.getId());
                    rc.setCourseId(x.id());
                    rc.setCode(x.code());
                    rc.setTitle(x.title());
                    rc.setUnits(x.units());
                    rc.setKind(RegistrationCourse.Kind.valueOf(x.kind().name()));
                    rc.setCarryOverId(o.carryOverIds().get(x.id()));
                    courses.save(rc);
                }
                event(reg, "SUBMITTED", "Registered " + r.courses().size() + " courses, " + r.totalUnits() + " units" + (g.late() ? " (late registration)" : ""),
                        s.fullName(), r.courses().stream().map(CourseRules.Course::code).toList());
                audit.record(new AuditService.Entry(tenant, actor, "REGISTRATION_SUBMITTED", "registration", reg.getId(),
                        s.fullName() + " registered " + r.courses().size() + " courses (" + r.totalUnits() + " units) for " + label, null,
                        Map.of("slipNumber", reg.getSlipNumber(), "courses", r.courses().stream().map(CourseRules.Course::code).toList())));
            });
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "already_registered", "You have already registered for this semester. Ask for a course change instead.");
        }
        LocalDate changes = c.window().changesCloseOn();
        Map<String, Object> v = new HashMap<>();
        v.put("firstName", s.firstName());
        v.put("semesterLabel", label);
        v.put("courseCount", r.courses().size());
        v.put("totalUnits", r.totalUnits());
        v.put("slipNumber", reg.getSlipNumber());
        v.put("slipUrl", tenants.portal() + "/registration/slip");
        v.put("courseList", String.join("\n", r.courses().stream().map(x -> x.code() + "  " + x.title() + " (" + x.units() + ")").toList()));
        v.put("changesCloseOn", Dates.full(changes));
        notifications.send(new PeerDtos.Message(s.userId(), s.email(), "registration.confirmed", v, "/registration", tenants.look()));
        return view(tx.execute(t -> registrations.findInTenant(reg.getId()).orElseThrow()), c, true);
    }

    void event(Registration reg, String action, String summary, String actor, List<String> codes) {
        RegistrationEvent e = new RegistrationEvent();
        e.setId(UUID.randomUUID());
        e.setRegistrationId(reg.getId());
        e.setAction(action);
        e.setSummary(summary.length() > 500 ? summary.substring(0, 500) : summary);
        e.setSlipVersion(reg.getSlipVersion());
        e.setActorName(actor);
        e.setCourses(Views.json(codes));
        e.setCreatedAt(clock.instant());
        events.save(e);
    }

    /** REG-26-1-0153: the session's first year, the semester, the last group of the matric number; -2, -3 … when taken. */
    String slipNumber(String sessionName, int semester, String matric) {
        String base = slipBase(sessionName, semester, matric);
        String n = base;
        for (int i = 2; registrations.slipTaken(n); i++) n = base + "-" + i;
        return n;
    }

    static String slipBase(String sessionName, int semester, String matric) {
        String yy = sessionName != null && sessionName.length() >= 4 ? sessionName.substring(2, 4) : "00";
        String[] parts = matric.split("[/\\-.]");
        String last = parts[parts.length - 1].replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (last.isEmpty()) last = matric.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return "REG-" + yy + "-" + semester + "-" + last;
    }

    String verificationCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i == 4) sb.append('-');
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            if (registrations.byCode(sb.toString()).isEmpty()) return sb.toString();
        }
        throw new IllegalStateException("no free verification code");
    }

    // ---------------------------------------------------------------- the registration, the slip

    public Registrations mine(CurrentUser cu) {
        StudentRef s = me(cu);
        LocalDate today = calendar.today();
        return new Registrations(tx.execute(t -> registrations.allForStudent(s.id())).stream().map(r -> view(r, today, false)).toList());
    }

    public RegistrationView mine(CurrentUser cu, UUID id) {
        StudentRef s = me(cu);
        Registration r = tx.execute(t -> registrations.findInTenant(id).filter(x -> x.getStudentId().equals(s.id())).orElseThrow(ApiException::notFound));
        return view(r, calendar.today(), true);
    }

    public Slip mySlip(CurrentUser cu, UUID id) {
        StudentRef s = me(cu);
        Registration r = tx.execute(t -> registrations.findInTenant(id).filter(x -> x.getStudentId().equals(s.id())).orElseThrow(ApiException::notFound));
        return slip(r);
    }

    public record Slip(String fileName, byte[] pdf) {}

    public Slip slip(Registration r) {
        List<RegistrationCourse> list = Views.ordered(tx.execute(t -> courses.forRegistration(r.getId())));
        PeerDtos.TenantView t = tenants.tenant();
        List<String[]> details = new ArrayList<>();
        details.add(new String[]{"Student", r.getStudentName()});
        details.add(new String[]{"Matric no.", r.getMatricNumber()});
        details.add(new String[]{"Programme", r.getProgrammeName() + " · " + r.getLevel() + "L"});
        details.add(new String[]{"Study centre", r.getCentreName() == null ? "" : r.getCentreName()});
        details.add(new String[]{"Slip no.", r.getSlipNumber()});
        details.add(new String[]{"Submitted", Dates.plain(Dates.lagos(r.getSubmittedAt())) + ", " + time(r.getSubmittedAt())});
        if (r.getSlipVersion() > 1 && r.getChangedAt() != null) {
            details.add(new String[]{"Updated", Dates.plain(Dates.lagos(r.getChangedAt())) + " (course change approved)"});
        }
        if (r.isLate()) details.add(new String[]{"Registration", "Late registration (approved)"});
        String portal = tenants.portal();
        String host = portal.replaceFirst("^https?://", "");
        String footer = "Verify this slip at " + (host.isEmpty() ? "the ODeL portal" : host) + "/verify · code " + r.getVerificationCode()
                + " · generated " + Dates.plain(calendar.today());
        byte[] pdf = SlipPdf.render(new SlipPdf.Data(t.name(), "Directorate of Distance & E-learning", tenants.logo(), t.primaryColor(),
                "Course registration slip · " + r.getSessionName() + " " + (r.getSemesterNumber() == 1 ? "first" : "second") + " semester",
                details, list.stream().map(c -> new SlipPdf.Line(c.getCode(), c.getTitle(), c.getUnits(), Views.kindLabel(c.getKind()))).toList(),
                r.getTotalUnits(), footer));
        return new Slip(r.getSlipNumber() + ".pdf", pdf);
    }

    private static String time(Instant i) {
        return String.format(Locale.UK, "%1$tH:%1$tM", i.atZone(Dates.LAGOS));
    }

    public Verification verify(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (c.length() == 8 && !c.contains("-")) c = c.substring(0, 4) + "-" + c.substring(4);
        String k = c;
        Registration r = tx.execute(t -> registrations.byCode(k).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found",
                "We could not find a slip with this code. Check the code on the slip.")));
        return new Verification(true, r.getSlipNumber(), r.getStudentName(), r.getMatricNumber(), r.getProgrammeName() + " · " + r.getLevel() + "L",
                CalendarService.label(r.getSessionName(), r.getSemesterNumber()), r.getCourseCount(), r.getTotalUnits(), r.getSubmittedAt(),
                r.getUpdatedAt(), r.getSlipVersion());
    }

    // ---------------------------------------------------------------- views

    RegistrationView view(Registration r, CalendarService.Choice c, boolean history) {
        return view(r, c.today(), history, c.semesterId() != null && c.semesterId().equals(r.getSemesterId()) ? c.window().changesCloseOn() : null);
    }

    public RegistrationView view(Registration r, LocalDate today, boolean history) {
        LocalDate changes;
        try {
            changes = calendar.window(academic.semester(r.getSemesterId()), today).changesCloseOn();
        } catch (RuntimeException e) {
            changes = null;
        }
        return view(r, today, history, changes);
    }

    RegistrationView view(Registration r, LocalDate today, boolean history, LocalDate changesCloseOn) {
        return tx.execute(t -> {
            // One course change at a time: while one waits for a decision the student is not offered another.
            boolean changeWaiting = requests.forStudent(r.getStudentId(), r.getSemesterId()).stream()
                    .anyMatch(q -> q.getKind() == RegistrationRequest.Kind.CHANGE && q.getStatus() == RegistrationRequest.Status.PENDING);
            return Views.registration(r, courses.forRegistration(r.getId()), history ? events.forRegistration(r.getId()) : null, changesCloseOn, today, changeWaiting);
        });
    }

    static StudentView studentView(StudentRef s) {
        return new StudentView(s.id(), s.matricNumber(), s.fullName(), new Ref(s.programmeId(), s.programmeCode(), s.programmeName()), s.level(),
                s.level() + "L", new Ref(s.centreId(), s.centreCode(), s.centreName()));
    }
}
