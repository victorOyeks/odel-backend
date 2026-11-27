package com.odel.academic.service;

import com.odel.academic.dto.SessionDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.academic.client.*;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.audit.AuditService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Sessions and semesters (W-11, W-11b, W-11c). One ACTIVE session at a time; making another active archives it. */
@Service
public class SessionService {
    private final SessionRepository sessions;
    private final SemesterRepository semesters;
    private final StaffAssignmentRepository assignments;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public SessionService(SessionRepository sessions, SemesterRepository semesters, StaffAssignmentRepository assignments,
                          AuditService audit, Clock clock, OdelProperties props) {
        this.sessions = sessions;
        this.semesters = semesters;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
        this.zone = ZoneId.of(props.timeZone());
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** The session being set up or run: the active one, otherwise the newest draft. */
    @Transactional(readOnly = true)
    public Optional<AcademicSession> current() {
        Optional<AcademicSession> active = sessions.findFirstByStatus(AcademicSession.Status.ACTIVE);
        if (active.isPresent()) {
            // A newer draft (next session being prepared) is what setup works on.
            Optional<AcademicSession> draft = sessions.newestFirst().stream().filter(s -> s.getStatus() == AcademicSession.Status.DRAFT)
                    .filter(s -> s.getStartsOn() == null || active.get().getStartsOn() == null || s.getStartsOn().isAfter(active.get().getStartsOn()))
                    .findFirst();
            return draft.isPresent() ? draft : active;
        }
        return sessions.newestFirst().stream().filter(s -> s.getStatus() == AcademicSession.Status.DRAFT).findFirst();
    }

    public AcademicSession resolve(UUID sessionId) {
        if (sessionId != null) return sessions.findById(sessionId).orElseThrow(ApiException::notFound);
        return current().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "no_session",
                "Set up the session first (Academic setup → Sessions & semesters)."));
    }

    @Transactional(readOnly = true)
    public List<SessionView> list() {
        List<AcademicSession> all = sessions.newestFirst();
        Map<UUID, List<Semester>> sems = new HashMap<>();
        semesters.findBySessionIdIn(all.stream().map(AcademicSession::getId).toList())
                .forEach(s -> sems.computeIfAbsent(s.getSessionId(), k -> new ArrayList<>()).add(s));
        UUID cur = current().map(AcademicSession::getId).orElse(null);
        return all.stream().map(s -> view(s, sems.getOrDefault(s.getId(), List.of()), s.getId().equals(cur))).toList();
    }

    @Transactional(readOnly = true)
    public SessionView get(UUID id) {
        AcademicSession s = sessions.findById(id).orElseThrow(ApiException::notFound);
        return view(s, semesters.findBySessionIdOrderByNumber(id), current().map(c -> c.getId().equals(id)).orElse(false));
    }

    public SessionView view(AcademicSession s, List<Semester> sems, boolean current) {
        List<Semester> sorted = new ArrayList<>(sems);
        sorted.sort(Comparator.comparingInt(Semester::getNumber));
        List<SemesterView> sv = sorted.stream().map(x -> new SemesterView(x.getId(), x.getNumber(), x.getNumber() == 1 ? "First semester" : "Second semester",
                x.getStartsOn(), x.getEndsOn(), x.getRegOpensOn() == null ? null : new RegistrationView(x.getRegOpensOn(), x.getRegClosesOn(), x.getLateRegClosesOn()))).toList();
        List<String> parts = new ArrayList<>();
        for (Semester x : sorted) {
            String n = x.getNumber() == 1 ? "1st" : "2nd";
            parts.add(x.getStartsOn() != null && x.getEndsOn() != null
                    ? n + ": " + SessionDates.month(x.getStartsOn()) + "–" + SessionDates.month(x.getEndsOn()) : n);
        }
        return new SessionView(s.getId(), s.getName(), s.getStatus().name(), s.getStartsOn(), s.getEndsOn(), current, sv,
                String.join(" · ", parts), registrationLabel(sorted), complete(sorted));
    }

    static boolean complete(List<Semester> sems) {
        boolean both = sems.size() == 2 && sems.stream().allMatch(x -> x.getStartsOn() != null && x.getEndsOn() != null);
        return both && sems.stream().anyMatch(x -> x.getRegOpensOn() != null);
    }

    /** The window students will meet next: "2 Feb – 16 Feb 2027"; "Closed" once past; "Not set". */
    String registrationLabel(List<Semester> sems) {
        LocalDate today = today();
        List<Semester> withWindow = sems.stream().filter(x -> x.getRegOpensOn() != null).toList();
        if (withWindow.isEmpty()) return "Not set";
        for (Semester x : withWindow) {
            LocalDate end = x.getLateRegClosesOn() != null ? x.getLateRegClosesOn() : x.getRegClosesOn();
            if (!end.isBefore(today)) return SessionDates.DAY.format(x.getRegOpensOn()).replaceAll(" \\d{4}$", "") + " – " + SessionDates.DAY.format(x.getRegClosesOn());
        }
        return "Closed";
    }

    /** "registration 2–16 Feb" for the checklist. */
    static String shortRegistration(List<Semester> sems) {
        return sems.stream().filter(x -> x.getRegOpensOn() != null).min(Comparator.comparingInt(Semester::getNumber))
                .map(x -> "registration " + SessionDates.range(x.getRegOpensOn(), x.getRegClosesOn(), false)).orElse(null);
    }

    @Transactional
    public SessionView create(SessionIn in, AuditService.Actor actor) {
        boolean activate = Boolean.TRUE.equals(in.makeActive());
        validate(in, null, activate);
        AcademicSession s = new AcademicSession();
        s.setId(UUID.randomUUID());
        s.setName(in.name().trim());
        s.setStatus(AcademicSession.Status.DRAFT);
        s.setCreatedAt(clock.instant());
        s.setUpdatedAt(clock.instant());
        s.setCreatedBy(actor.userId());
        apply(s, in);
        sessions.saveAndFlush(s);
        writeSemesters(s, in);
        if (activate) activate(s, actor, false);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "SESSION_CREATED", "session", s.getId(),
                "Created the " + s.getName() + " session" + (activate ? " and made it active" : ""), null, Map.of("name", s.getName())));
        return get(s.getId());
    }

    @Transactional
    public SessionView update(UUID id, SessionIn in, AuditService.Actor actor) {
        AcademicSession s = sessions.findById(id).orElseThrow(ApiException::notFound);
        boolean activate = Boolean.TRUE.equals(in.makeActive()) || s.getStatus() == AcademicSession.Status.ACTIVE;
        validate(in, s.getId(), activate);
        SessionView before = get(id);
        s.setName(in.name().trim());
        s.setUpdatedAt(clock.instant());
        apply(s, in);
        semesters.deleteBySessionId(s.getId());
        semesters.flush();
        writeSemesters(s, in);
        if (Boolean.TRUE.equals(in.makeActive()) && s.getStatus() != AcademicSession.Status.ACTIVE) activate(s, actor, false);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "SESSION_UPDATED", "session", s.getId(),
                "Changed the " + s.getName() + " session's dates", before.semesters(), null));
        return get(s.getId());
    }

    @Transactional
    public SessionView activate(UUID id, AuditService.Actor actor) {
        AcademicSession s = sessions.findById(id).orElseThrow(ApiException::notFound);
        if (!complete(semesters.findBySessionIdOrderByNumber(id))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "incomplete", "Add both semesters' dates and the registration window before making the session active.");
        }
        activate(s, actor, true);
        return get(id);
    }

    private void activate(AcademicSession s, AuditService.Actor actor, boolean audited) {
        sessions.findFirstByStatus(AcademicSession.Status.ACTIVE).filter(a -> !a.getId().equals(s.getId())).ifPresent(a -> {
            a.setStatus(AcademicSession.Status.ARCHIVED);
            a.setUpdatedAt(clock.instant());
            sessions.saveAndFlush(a);
        });
        s.setStatus(AcademicSession.Status.ACTIVE);
        s.setUpdatedAt(clock.instant());
        sessions.saveAndFlush(s);
        if (audited) {
            audit.record(new AuditService.Entry(TenantContext.require(), actor, "SESSION_ACTIVATED", "session", s.getId(),
                    "Made " + s.getName() + " the active session", null, null));
        }
    }

    @Transactional
    public void delete(UUID id, AuditService.Actor actor) {
        AcademicSession s = sessions.findById(id).orElseThrow(ApiException::notFound);
        if (s.getStatus() != AcademicSession.Status.DRAFT || assignments.existsBySessionId(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "in_use", s.getStatus() != AcademicSession.Status.DRAFT
                    ? "Only a draft session can be deleted." : "This session already has staff assigned; it cannot be deleted.");
        }
        sessions.delete(s);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "SESSION_DELETED", "session", id, "Deleted the draft session " + s.getName(), null, null));
    }

    private void validate(SessionIn in, UUID self, boolean complete) {
        Map<String, String> f = SessionDates.check(in.name(), in.semesters(), complete);
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Some details need fixing.", f);
        String name = in.name().trim();
        sessions.findByName(name).filter(x -> !x.getId().equals(self)).ifPresent(x -> {
            throw new ApiException(HttpStatus.CONFLICT, "name_taken", "There is already a " + name + " session.");
        });
        LocalDate[] range = range(in);
        if (range != null) {
            for (AcademicSession other : sessions.findAll()) {
                if (other.getId().equals(self)) continue;
                if (SessionDates.overlap(range[0], range[1], other.getStartsOn(), other.getEndsOn())) {
                    throw new ApiException(HttpStatus.CONFLICT, "overlap", "These dates overlap " + other.getName() + " ("
                            + SessionDates.DAY.format(other.getStartsOn()) + " – " + SessionDates.DAY.format(other.getEndsOn())
                            + "). Change the dates so sessions follow one another.");
                }
            }
        }
    }

    private static LocalDate[] range(SessionIn in) {
        LocalDate start = null, end = null;
        if (in.semesters() == null) return null;
        for (SemesterIn s : in.semesters()) {
            if (s.startsOn() != null && (start == null || s.startsOn().isBefore(start))) start = s.startsOn();
            if (s.endsOn() != null && (end == null || s.endsOn().isAfter(end))) end = s.endsOn();
        }
        return start == null || end == null ? null : new LocalDate[]{start, end};
    }

    private static void apply(AcademicSession s, SessionIn in) {
        LocalDate[] r = range(in);
        s.setStartsOn(r == null ? null : r[0]);
        s.setEndsOn(r == null ? null : r[1]);
    }

    private void writeSemesters(AcademicSession s, SessionIn in) {
        if (in.semesters() == null) return;
        for (SemesterIn x : in.semesters()) {
            Semester m = new Semester();
            m.setId(UUID.randomUUID());
            m.setSessionId(s.getId());
            m.setNumber(x.number());
            m.setStartsOn(x.startsOn());
            m.setEndsOn(x.endsOn());
            if (x.registration() != null && x.registration().opensOn() != null) {
                m.setRegOpensOn(x.registration().opensOn());
                m.setRegClosesOn(x.registration().closesOn());
                m.setLateRegClosesOn(x.registration().lateClosesOn());
            }
            semesters.save(m);
        }
        semesters.flush();
    }
}
