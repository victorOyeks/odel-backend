package com.odel.registration.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.files.StorageService;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.registration.client.IdentityClient;
import com.odel.registration.client.NotificationClient;
import com.odel.registration.client.TenantClient;
import com.odel.registration.dto.InternalDtos;
import com.odel.registration.dto.PeerDtos;
import com.odel.registration.dto.PeerDtos.StudentRef;
import com.odel.registration.dto.RequestDtos.*;
import com.odel.registration.entity.*;
import com.odel.registration.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Late registration (R-06 … R-07d) and course change requests (R-09 … R-09e), and the coordinator's side (A-03 … A-03e):
 * the queue (their centre only), approve and decline. An approved change is applied at once and a new slip version made.
 */
@Service
public class RequestService {
    private static final long MAX_ATTACHMENT = 5L * 1024 * 1024;
    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "pdf", "application/pdf");

    private final RegistrationService registration;
    private final CalendarService calendar;
    private final RulesService rules;
    private final CoordinatorDirectory coordinators;
    private final RequestRepository requests;
    private final RegistrationRepository registrations;
    private final RegistrationCourseRepository courses;
    private final StorageService storage;
    private final IdentityClient identity;
    private final NotificationClient notifications;
    private final TenantClient tenants;
    private final ActorService actors;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RequestService(RegistrationService registration, CalendarService calendar, RulesService rules,
                          CoordinatorDirectory coordinators, RequestRepository requests, RegistrationRepository registrations,
                          RegistrationCourseRepository courses, StorageService storage, IdentityClient identity, NotificationClient notifications,
                          TenantClient tenants, ActorService actors, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.registration = registration;
        this.calendar = calendar;
        this.rules = rules;
        this.coordinators = coordinators;
        this.requests = requests;
        this.registrations = registrations;
        this.courses = courses;
        this.storage = storage;
        this.identity = identity;
        this.notifications = notifications;
        this.tenants = tenants;
        this.actors = actors;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- attachment upload (C30)

    public UploadLink uploadLink(CurrentUser cu, UploadRequest in) {
        StudentRef s = registration.me(cu);
        Map<String, String> f = new LinkedHashMap<>();
        String name = in.fileName() == null ? "" : in.fileName().trim();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (name.isEmpty()) f.put("fileName", "Choose a file.");
        else if (!TYPES.containsKey(ext)) f.put("fileName", "Attach a JPG, PNG or PDF file.");
        if (in.size() != null && in.size() > MAX_ATTACHMENT) f.put("size", "The file is over 5 MB. Attach a smaller photo or PDF.");
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        String key = prefix(s) + UUID.randomUUID() + "." + ext;
        Duration ttl = Duration.ofMinutes(15);
        return new UploadLink(key, storage.presignPut(key, ttl), "PUT", Map.of("Content-Type", TYPES.get(ext)), clock.instant().plus(ttl));
    }

    private static String prefix(StudentRef s) {
        return "registration/" + TenantContext.require() + "/" + s.id() + "/";
    }

    // ---------------------------------------------------------------- late registration (R-06)

    public RequestView late(CurrentUser cu, LateRequestIn in) {
        StudentRef s = registration.me(cu);
        CalendarService.Choice c = calendar.pick(in.semesterId());
        if (c.none()) throw new ApiException(HttpStatus.CONFLICT, "not_set", "Registration for this semester is not set up yet. Check back later.");
        Window w = c.window();
        switch (w.state()) {
            case NOT_SET -> throw new ApiException(HttpStatus.CONFLICT, "not_late", "Registration for this semester is not set up yet. Check back later.");
            case NOT_OPEN -> throw new ApiException(HttpStatus.CONFLICT, "not_late", "Registration has not opened yet. It opens " + Dates.full(w.opensOn()) + ".");
            case OPEN -> throw new ApiException(HttpStatus.CONFLICT, "not_late", "Registration is open: register now.");
            case CLOSED -> throw new ApiException(HttpStatus.CONFLICT, "not_late", "Late registration closed on "
                    + Dates.weekday(w.lateClosesOn() != null ? w.lateClosesOn() : w.closesOn()) + ". Contact your study centre.");
            default -> { }
        }
        if (tx.execute(t -> registrations.forStudent(s.id(), c.semesterId()).isPresent())) {
            throw new ApiException(HttpStatus.CONFLICT, "already_registered", "You have already registered for this semester.");
        }
        List<RegistrationRequest> mine = tx.execute(t -> requests.forStudent(s.id(), c.semesterId()));
        if (mine.stream().anyMatch(r -> r.getKind() == RegistrationRequest.Kind.LATE && r.getStatus() == RegistrationRequest.Status.PENDING)) {
            throw new ApiException(HttpStatus.CONFLICT, "request_pending", "You already asked; the decision will come by email.");
        }
        if (mine.stream().anyMatch(r -> r.getKind() == RegistrationRequest.Kind.LATE && r.getStatus() == RegistrationRequest.Status.APPROVED)) {
            throw new ApiException(HttpStatus.CONFLICT, "already_approved", "Your late registration is already approved: register your courses now.");
        }
        Map<String, String> f = new LinkedHashMap<>();
        String category = in.reasonCategory() == null ? "" : in.reasonCategory().trim().toUpperCase(Locale.ROOT);
        if (!Views.REASONS.containsKey(category)) f.put("reasonCategory", "Choose a reason.");
        String reason = clean(in.reason());
        if (reason.length() < 10 || reason.length() > 1000) f.put("reason", "Tell your coordinator what happened (10 to 1,000 characters).");
        Attachment a = in.attachment();
        if (a != null && a.key() != null && !a.key().isBlank()) {
            if (!a.key().startsWith(prefix(s)) || a.key().contains("..") || storage.size(a.key()).isEmpty()) {
                f.put("attachment", "Upload the file again.");
            } else if (storage.size(a.key()).getAsLong() > MAX_ATTACHMENT) {
                f.put("attachment", "The file is over 5 MB. Attach a smaller photo or PDF.");
            }
        } else a = null;
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);

        RegistrationRequest r = base(s, c, RegistrationRequest.Kind.LATE, reason);
        r.setReasonCategory(category);
        if (a != null) {
            String fileName = a.fileName() == null || a.fileName().isBlank() ? a.key().substring(a.key().lastIndexOf('/') + 1) : a.fileName().trim();
            r.setAttachmentKey(a.key());
            r.setAttachmentName(fileName.length() > 300 ? fileName.substring(0, 300) : fileName);
            r.setAttachmentSize(storage.size(a.key()).getAsLong());
            r.setAttachmentType(a.contentType() != null ? a.contentType() : TYPES.get(Views.type(fileName) == null ? "" : Views.type(fileName).toLowerCase(Locale.ROOT)));
        }
        save(r, cu, s, "REGISTRATION_LATE_REQUESTED", s.fullName() + " asked for late registration (" + Views.REASONS.get(category) + ")");
        tellCoordinator(r, "late registration", Views.REASONS.get(category) + ": " + shorten(reason));
        return view(r);
    }

    // ---------------------------------------------------------------- course change (R-09)

    public RequestView change(CurrentUser cu, ChangeRequestIn in) {
        StudentRef s = registration.me(cu);
        if (in.registrationId() == null) throw ApiException.badRequest("invalid", "Choose the registration to change.");
        Registration reg = tx.execute(t -> registrations.findInTenant(in.registrationId()).filter(x -> x.getStudentId().equals(s.id()))
                .orElseThrow(ApiException::notFound));
        CalendarService.Choice c = calendar.pick(reg.getSemesterId());
        if (!c.window().changesOpen(c.today())) {
            throw new ApiException(HttpStatus.CONFLICT, "changes_closed", "Course changes closed on " + Dates.weekday(c.window().changesCloseOn()) + ".");
        }
        if (tx.execute(t -> requests.forStudent(s.id(), reg.getSemesterId())).stream()
                .anyMatch(r -> r.getKind() == RegistrationRequest.Kind.CHANGE && r.getStatus() == RegistrationRequest.Status.PENDING)) {
            throw new ApiException(HttpStatus.CONFLICT, "request_pending", "You already have a course change waiting for a decision. Cancel it to send another.");
        }
        String reason = clean(in.reason());
        CourseRules.Limits l = rules.limits(s.programmeId(), s.level());
        List<RegistrationCourse> current = tx.execute(t -> courses.forRegistration(reg.getId()));
        RegistrationService.Offer o = registration.offer(s, c);
        CourseRules.Result result = CourseRules.change(asRules(current), o.offered(), in.dropCourseIds(), in.addCourseIds(), l);
        Map<String, String> f = new LinkedHashMap<>();
        if (reason.length() < 10 || reason.length() > 1000) f.put("reason", "Say why you want the change (10 to 1,000 characters).");
        if (!result.ok()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid", result.problems().get(0).message(), f.isEmpty() ? null : f,
                    Map.of("problems", result.problems()));
        }
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        Map<UUID, CourseRules.Course> catalogue = new HashMap<>();
        o.offered().forEach(x -> catalogue.put(x.id(), x));
        asRules(current).forEach(x -> catalogue.put(x.id(), x));
        List<CourseRef> drop = refs(in.dropCourseIds(), catalogue), add = refs(in.addCourseIds(), catalogue);

        RegistrationRequest r = base(s, c, RegistrationRequest.Kind.CHANGE, reason);
        r.setRegistrationId(reg.getId());
        r.setDropCourses(Views.json(drop));
        r.setAddCourses(Views.json(add));
        r.setUnitsBefore(reg.getTotalUnits());
        r.setUnitsAfter(result.totalUnits());
        String summary = Views.changeSummary(drop, add);
        save(r, cu, s, "REGISTRATION_CHANGE_REQUESTED", s.fullName() + " asked for a course change: " + summary);
        tellCoordinator(r, "a course change", summary + " (" + r.getUnitsBefore() + " → " + r.getUnitsAfter() + " units)");
        return view(r);
    }

    private static List<CourseRef> refs(Collection<UUID> ids, Map<UUID, CourseRules.Course> catalogue) {
        if (ids == null) return List.of();
        return new LinkedHashSet<>(ids).stream().map(catalogue::get).filter(Objects::nonNull)
                .map(c -> new CourseRef(c.id(), c.code(), c.title(), c.units())).toList();
    }

    static List<CourseRules.Course> asRules(List<RegistrationCourse> list) {
        return list.stream().map(c -> new CourseRules.Course(c.getCourseId(), c.getCode(), c.getTitle(), c.getUnits(),
                CourseRules.Kind.valueOf(c.getKind().name()))).toList();
    }

    private RegistrationRequest base(StudentRef s, CalendarService.Choice c, RegistrationRequest.Kind kind, String reason) {
        RegistrationRequest r = new RegistrationRequest();
        r.setId(UUID.randomUUID());
        r.setKind(kind);
        r.setStatus(RegistrationRequest.Status.PENDING);
        r.setStudentId(s.id());
        r.setUserId(s.userId());
        r.setMatricNumber(s.matricNumber());
        r.setStudentName(s.fullName());
        r.setStudentFirstName(s.firstName());
        r.setStudentEmail(s.email());
        r.setProgrammeCode(s.programmeCode());
        r.setProgrammeName(s.programmeName());
        r.setLevel(s.level());
        r.setCentreId(s.centreId());
        r.setCentreName(s.centreName());
        r.setSemesterId(c.semesterId());
        r.setSessionName(c.semester().sessionName());
        r.setSemesterNumber(c.semester().number());
        r.setReason(reason);
        r.setCreatedAt(clock.instant());
        return r;
    }

    private void save(RegistrationRequest r, CurrentUser cu, StudentRef s, String action, String summary) {
        AuditService.Actor actor = new AuditService.Actor(cu.userId(), cu.tenantId(), s.fullName(), "STUDENT");
        tx.executeWithoutResult(t -> {
            requests.save(r);
            audit.record(new AuditService.Entry(TenantContext.require(), actor, action, "registration_request", r.getId(), summary, null, null));
        });
    }

    /** The coordinator of the student's centre hears of the request (none = it waits in the ODeL office queue, A-03e). */
    private void tellCoordinator(RegistrationRequest r, String kindLabel, String summary) {
        Optional<UUID> coordinator;
        try {
            coordinator = coordinators.coordinatorOf(r.getCentreId());
        } catch (RuntimeException e) {
            return;
        }
        if (coordinator.isEmpty()) return;
        PeerDtos.StaffView staff;
        try {
            staff = identity.staff(coordinator.get());
        } catch (RuntimeException e) {
            return;
        }
        Map<String, Object> v = new HashMap<>();
        v.put("fullName", staff.fullName());
        v.put("studentName", r.getStudentName());
        v.put("matricNumber", r.getMatricNumber());
        v.put("kindLabel", kindLabel);
        v.put("centreName", r.getCentreName() == null ? "" : r.getCentreName());
        v.put("summary", summary);
        v.put("queueUrl", tenants.portal() + "/staff/registration/approvals");
        notifications.send(new PeerDtos.Message(staff.id(), staff.email(), "registration.request_new", v, "/staff/registration/approvals", tenants.look()));
    }

    // ---------------------------------------------------------------- the student's own requests

    public Requests mine(CurrentUser cu, UUID semesterId) {
        StudentRef s = registration.me(cu);
        List<RegistrationRequest> list = tx.execute(t -> semesterId == null ? requests.allForStudent(s.id()) : requests.forStudent(s.id(), semesterId));
        Map<UUID, UUID> coord = coordinatorsOrEmpty();
        return new Requests(list.stream().map(r -> view(r, coord)).toList());
    }

    public RequestView one(CurrentUser cu, UUID id) {
        StudentRef s = registration.me(cu);
        return view(own(s, id));
    }

    public Link myAttachment(CurrentUser cu, UUID id) {
        StudentRef s = registration.me(cu);
        return link(own(s, id));
    }

    public RequestView cancel(CurrentUser cu, UUID id) {
        StudentRef s = registration.me(cu);
        RegistrationRequest r = own(s, id);
        if (r.getStatus() != RegistrationRequest.Status.PENDING) throw notPending(r);
        r.setStatus(RegistrationRequest.Status.CANCELLED);
        save(r, cu, s, "REGISTRATION_REQUEST_CANCELLED", s.fullName() + " cancelled their " + (r.getKind() == RegistrationRequest.Kind.LATE
                ? "late registration request" : "course change request"));
        return view(r);
    }

    private RegistrationRequest own(StudentRef s, UUID id) {
        return tx.execute(t -> requests.findInTenant(id).filter(x -> x.getStudentId().equals(s.id())).orElseThrow(ApiException::notFound));
    }

    // ---------------------------------------------------------------- staff: the queue (A-03, A-03e)

    public com.odel.registration.dto.RequestDtos.Queue queue(CurrentUser cu, String kind, String status, UUID centreId, Boolean noCoordinator, UUID semesterId, int page, int size) {
        staff(cu);
        Map<UUID, UUID> coord = coordinators.byCentre();
        Set<UUID> visible = ApprovalScope.visibleCentres(cu.activeRole(), cu.userId(), coord);
        RegistrationRequest.Kind k = parse(kind, RegistrationRequest.Kind.class, "kind is LATE or CHANGE.");
        RegistrationRequest.Status st = status == null || status.isBlank() ? RegistrationRequest.Status.PENDING
                : status.equalsIgnoreCase("ALL") ? null : parse(status, RegistrationRequest.Status.class, "status is PENDING, APPROVED, DECLINED, CANCELLED or ALL.");
        List<RegistrationRequest> all = tx.execute(t -> semesterId == null ? requests.newestFirst() : requests.forSemester(semesterId));
        List<RegistrationRequest> scoped = all.stream()
                .filter(r -> visible == null || (r.getCentreId() != null && visible.contains(r.getCentreId())))
                .filter(r -> centreId == null || centreId.equals(r.getCentreId()))
                .filter(r -> !Boolean.TRUE.equals(noCoordinator) || r.getCentreId() == null || !coord.containsKey(r.getCentreId()))
                .filter(r -> st == null || r.getStatus() == st).toList();
        Counts counts = new Counts(scoped.stream().filter(r -> r.getKind() == RegistrationRequest.Kind.LATE).count(),
                scoped.stream().filter(r -> r.getKind() == RegistrationRequest.Kind.CHANGE).count());
        List<RegistrationRequest> filtered = scoped.stream().filter(r -> k == null || r.getKind() == k).toList();
        int sz = Math.max(1, Math.min(size, 100)), p = Math.max(0, page);
        List<RequestView> items = filtered.stream().skip((long) p * sz).limit(sz).map(r -> view(r, coord)).toList();
        return new com.odel.registration.dto.RequestDtos.Queue(items, p, sz, filtered.size(), counts);
    }

    public RequestDetail detail(CurrentUser cu, UUID id) {
        staff(cu);
        RegistrationRequest r = visible(cu, id);
        Registration reg = tx.execute(t -> registrations.forStudent(r.getStudentId(), r.getSemesterId()).orElse(null));
        return new RequestDetail(view(r), reg == null ? null : registration.view(reg, calendar.today(), true));
    }

    public Link attachment(CurrentUser cu, UUID id) {
        staff(cu);
        return link(visible(cu, id));
    }

    private Link link(RegistrationRequest r) {
        if (r.getAttachmentKey() == null) throw new ApiException(HttpStatus.NOT_FOUND, "no_attachment", "This request has no attached file.");
        Duration ttl = Duration.ofMinutes(5);
        return new Link(storage.presignGet(r.getAttachmentKey(), ttl), clock.instant().plus(ttl));
    }

    private RegistrationRequest visible(CurrentUser cu, UUID id) {
        RegistrationRequest r = tx.execute(t -> requests.findInTenant(id).orElseThrow(ApiException::notFound));
        if (!ApprovalScope.canSee(cu.activeRole(), cu.userId(), r.getCentreId(), coordinators.byCentre())) throw ApiException.notFound();
        return r;
    }

    private static void staff(CurrentUser cu) {
        Access.require(cu, Role.ODEL_ADMIN, Role.ODEL_DIRECTOR, Role.CENTRE_COORDINATOR);
    }

    // ---------------------------------------------------------------- staff: decide (A-03b, A-03c, A-03d)

    public RequestView approve(CurrentUser cu, UUID id, DecisionIn in) {
        return decide(cu, id, true, in == null ? null : in.note());
    }

    public RequestView decline(CurrentUser cu, UUID id, DecisionIn in) {
        String note = in == null || in.note() == null ? "" : clean(in.note());
        if (note.length() < 5 || note.length() > 500) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Tell the student why (5 to 500 characters).",
                    Map.of("note", "Tell the student why (5 to 500 characters)."));
        }
        return decide(cu, id, false, note);
    }

    private RequestView decide(CurrentUser cu, UUID id, boolean approve, String note) {
        staff(cu);
        RegistrationRequest r = visible(cu, id);
        if (!ApprovalScope.canDecide(cu.activeRole(), cu.userId(), r.getCentreId(), coordinators.byCentre())) throw ApiException.forbidden();
        if (r.getStatus() != RegistrationRequest.Status.PENDING) throw notPending(r);
        AuditService.Actor actor = actors.actor(cu);
        LocalDate today = calendar.today();
        String cleanNote = note == null || note.isBlank() ? null : clean(note);
        String label = CalendarService.label(r.getSessionName(), r.getSemesterNumber());

        if (r.getKind() == RegistrationRequest.Kind.LATE) {
            if (approve) {
                CalendarService.Choice c = calendar.pick(r.getSemesterId());
                LocalDate lateClose = c.window().lateClosesOn() != null ? c.window().lateClosesOn() : c.window().closesOn();
                r.setRegisterBy(lateClose != null && !today.isAfter(lateClose) ? lateClose : today.plusDays(3));
            }
            finish(r, approve, cleanNote, actor, cu, approve ? "REGISTRATION_LATE_APPROVED" : "REGISTRATION_LATE_DECLINED",
                    actor.name() + (approve ? " approved " : " declined ") + r.getStudentName() + "'s late registration", null);
            Map<String, Object> v = vars(r, label, actor);
            if (approve) {
                v.put("decidedOn", Dates.day(today));
                v.put("registerBy", Dates.weekday(r.getRegisterBy()));
                v.put("registerUrl", tenants.portal() + "/registration");
                tellStudent(r, "registration.late_approved", v);
            } else {
                v.put("note", cleanNote);
                v.put("requestUrl", tenants.portal() + "/registration");
                tellStudent(r, "registration.late_declined", v);
            }
            return view(r);
        }

        // Course change: re-check against the registration as it is now, then apply.
        List<CourseRef> drop = Views.courses(r.getDropCourses()), add = Views.courses(r.getAddCourses());
        String summary = Views.changeSummary(drop, add);
        if (!approve) {
            finish(r, false, cleanNote, actor, cu, "REGISTRATION_CHANGE_DECLINED", actor.name() + " declined " + r.getStudentName() + "'s course change (" + summary + ")", null);
            Map<String, Object> v = vars(r, label, actor);
            v.put("changeSummary", summary);
            v.put("note", cleanNote);
            v.put("requestUrl", tenants.portal() + "/registration");
            tellStudent(r, "registration.change_declined", v);
            return view(r);
        }
        Registration reg = tx.execute(t -> registrations.findInTenant(r.getRegistrationId()).orElseThrow(ApiException::notFound));
        List<RegistrationCourse> current = tx.execute(t -> courses.forRegistration(reg.getId()));
        CourseRules.Limits l = rules.limits(reg.getProgrammeId(), reg.getLevel());
        List<CourseRules.Course> offeredAdds = add.stream().map(a -> new CourseRules.Course(a.courseId(), a.code(), a.title(), a.units(), CourseRules.Kind.ELECTIVE)).toList();
        List<CourseRules.Course> offered = new ArrayList<>(offeredAdds);
        CourseRules.Result result = CourseRules.change(asRules(current), offered, drop.stream().map(CourseRef::courseId).toList(),
                add.stream().map(CourseRef::courseId).toList(), l);
        if (!result.ok()) {
            throw new ApiException(HttpStatus.CONFLICT, "no_longer_fits", "This change no longer fits the student's registration: "
                    + result.problems().get(0).message(), null, Map.of("problems", result.problems()));
        }
        Set<UUID> dropIds = new HashSet<>(drop.stream().map(CourseRef::courseId).toList());
        finish(r, true, cleanNote, actor, cu, "REGISTRATION_CHANGE_APPROVED", actor.name() + " approved " + r.getStudentName() + "'s course change (" + summary + ")",
                () -> {
                    current.stream().filter(c -> dropIds.contains(c.getCourseId())).forEach(courses::delete);
                    for (CourseRef a : add) {
                        RegistrationCourse rc = new RegistrationCourse();
                        rc.setId(UUID.randomUUID());
                        rc.setRegistrationId(reg.getId());
                        rc.setCourseId(a.courseId());
                        rc.setCode(a.code());
                        rc.setTitle(a.title());
                        rc.setUnits(a.units());
                        rc.setKind(RegistrationCourse.Kind.ELECTIVE);
                        courses.save(rc);
                    }
                    Instant now = clock.instant();
                    reg.setTotalUnits(result.totalUnits());
                    reg.setCourseCount(result.courses().size());
                    reg.setSlipVersion(reg.getSlipVersion() + 1);
                    reg.setUpdatedAt(now);
                    reg.setChangedAt(now);
                    registrations.save(reg);
                    registration.event(reg, "CHANGE_APPLIED", summary + " (approved by " + actor.name() + ")", actor.name(),
                            result.courses().stream().map(CourseRules.Course::code).toList());
                });
        Map<String, Object> v = vars(r, label, actor);
        v.put("changeSummary", summary);
        v.put("totalUnits", result.totalUnits());
        v.put("slipUrl", tenants.portal() + "/registration/slip");
        tellStudent(r, "registration.change_approved", v);
        return view(r);
    }

    private void finish(RegistrationRequest r, boolean approve, String note, AuditService.Actor actor, CurrentUser cu, String action, String summary, Runnable more) {
        tx.executeWithoutResult(t -> {
            RegistrationRequest fresh = requests.findInTenant(r.getId()).orElseThrow();
            if (fresh.getStatus() != RegistrationRequest.Status.PENDING) throw notPending(fresh);
            r.setStatus(approve ? RegistrationRequest.Status.APPROVED : RegistrationRequest.Status.DECLINED);
            r.setDecidedAt(clock.instant());
            r.setDecidedBy(cu.userId());
            r.setDecidedByName(actor.name());
            r.setDecidedByRole(cu.activeRole().name());
            r.setDecisionNote(note);
            requests.save(r);
            if (more != null) more.run();
            audit.record(new AuditService.Entry(TenantContext.require(), actor, action, "registration_request", r.getId(), summary, null,
                    note == null ? null : Map.of("note", note)));
        });
    }

    private Map<String, Object> vars(RegistrationRequest r, String label, AuditService.Actor actor) {
        Map<String, Object> v = new HashMap<>();
        v.put("firstName", r.getStudentFirstName() == null ? r.getStudentName() : r.getStudentFirstName());
        v.put("semesterLabel", label);
        v.put("decidedBy", actor.name());
        return v;
    }

    private void tellStudent(RegistrationRequest r, String template, Map<String, Object> vars) {
        notifications.send(new PeerDtos.Message(r.getUserId(), r.getStudentEmail(), template, vars, "/registration", tenants.look()));
    }

    private static ApiException notPending(RegistrationRequest r) {
        String what = switch (r.getStatus()) {
            case APPROVED -> "already approved" + (r.getDecidedByName() == null ? "" : " by " + r.getDecidedByName());
            case DECLINED -> "already declined" + (r.getDecidedByName() == null ? "" : " by " + r.getDecidedByName());
            case CANCELLED -> "cancelled by the student";
            default -> "not waiting for a decision";
        };
        return new ApiException(HttpStatus.CONFLICT, "not_pending", "This request was " + what + ".");
    }

    // ---------------------------------------------------------------- centre move (student-service)

    public InternalDtos.CentreMovedResult centreMoved(UUID studentId, InternalDtos.CentreMoved in) {
        return tx.execute(t -> {
            int moved = 0;
            for (RegistrationRequest r : requests.pendingForStudent(studentId)) {
                r.setCentreId(in.centreId());
                r.setCentreName(in.centreName());
                requests.save(r);
                moved++;
            }
            int regs = 0;
            CalendarService.Choice c;
            try {
                c = calendar.pick(null);
            } catch (RuntimeException e) {
                c = null;
            }
            if (c != null && !c.none()) {
                Optional<Registration> reg = registrations.forStudent(studentId, c.semesterId());
                if (reg.isPresent()) {
                    reg.get().setCentreId(in.centreId());
                    reg.get().setCentreCode(in.centreCode());
                    reg.get().setCentreName(in.centreName());
                    registrations.save(reg.get());
                    regs = 1;
                }
            }
            return new InternalDtos.CentreMovedResult(moved, regs);
        });
    }

    // ---------------------------------------------------------------- views

    RequestView view(RegistrationRequest r) {
        return view(r, coordinatorsOrEmpty());
    }

    RequestView view(RegistrationRequest r, Map<UUID, UUID> coord) {
        Integer max = null;
        LocalDate registeredOn = null;
        if (r.getKind() == RegistrationRequest.Kind.CHANGE && r.getRegistrationId() != null) {
            Registration reg = tx.execute(t -> registrations.findInTenant(r.getRegistrationId()).orElse(null));
            if (reg != null) {
                max = rules.limits(reg.getProgrammeId(), reg.getLevel()).maxUnits();
                registeredOn = Dates.lagos(reg.getSubmittedAt());
            }
        }
        return Views.request(r, r.getCentreId() != null && coord.containsKey(r.getCentreId()), max, registeredOn);
    }

    private Map<UUID, UUID> coordinatorsOrEmpty() {
        try {
            return coordinators.byCentre();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static <E extends Enum<E>> E parse(String v, Class<E> type, String message) {
        if (v == null || v.isBlank()) return null;
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid", message);
        }
    }

    static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static String shorten(String s) {
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }
}
