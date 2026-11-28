package com.odel.student.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import com.odel.student.client.*;
import com.odel.student.dto.AcademicLookup;
import com.odel.student.dto.CentreMoveDtos.*;
import com.odel.student.dto.IdentityDtos;
import com.odel.student.dto.NotificationDtos;
import com.odel.student.entity.CentreMove;
import com.odel.student.entity.Student;
import com.odel.student.repository.CentreMoveRepository;
import com.odel.student.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * A-06 Move a student to another centre: with a reason and an effective date; the student and both coordinators (old and new
 * centre) are emailed; the move is kept as history; the student's open requests in registration-service follow the new centre.
 * The plain centre edit (PATCH /students/{id}) goes through here too.
 */
@Service
public class CentreMoveService {
    private static final Logger log = LoggerFactory.getLogger(CentreMoveService.class);
    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StudentRepository students;
    private final CentreMoveRepository moves;
    private final AcademicLookupService academic;
    private final CoordinatorClient coordinators;
    private final IdentityClient identity;
    private final NotificationClient notifications;
    private final TenantClient tenants;
    private final RegistrationClient registration;
    private final ActorService actors;
    private final AuditService audit;
    private final StudentService studentService;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CentreMoveService(StudentRepository students, CentreMoveRepository moves, AcademicLookupService academic, CoordinatorClient coordinators,
                             IdentityClient identity, NotificationClient notifications, TenantClient tenants, RegistrationClient registration,
                             ActorService actors, AuditService audit, @Lazy StudentService studentService, TransactionTemplate tx,
                             JdbcTemplate jdbc, Clock clock) {
        this.students = students;
        this.moves = moves;
        this.academic = academic;
        this.coordinators = coordinators;
        this.identity = identity;
        this.notifications = notifications;
        this.tenants = tenants;
        this.registration = registration;
        this.actors = actors;
        this.audit = audit;
        this.studentService = studentService;
        this.tx = tx;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- move (A-06)

    public MoveResult move(CurrentUser cu, UUID studentId, MoveRequest in) {
        Move m = record(cu, studentId, in, "reason");
        return new MoveResult(m, studentService.record(studentId));
    }

    /** Checks, records, emails, and applies the move when it is due. {@code reasonField} names the field a missing reason is reported on. */
    Move record(CurrentUser cu, UUID studentId, MoveRequest in, String reasonField) {
        AuditService.Actor actor = actors.actor(cu);
        Student s = tx.execute(t -> students.findInTenant(studentId).orElseThrow(ApiException::notFound));
        LocalDate today = LocalDate.now(clock.withZone(LAGOS));
        Map<String, String> f = new LinkedHashMap<>();
        Optional<AcademicLookup.Centre> to = in.centreCode() == null || in.centreCode().isBlank() ? Optional.empty() : academic.centre(in.centreCode());
        if (to.isEmpty()) f.put("centreCode", "Choose a centre from Centres.");
        String reason = in.reason() == null ? "" : in.reason().trim().replaceAll("\\s+", " ");
        if (reason.length() < 3 || reason.length() > 500) {
            f.put(reasonField, reasonField.equals("reason") ? "Say why the student is moving (3 to 500 characters)." : "Say why the student is moving centre.");
        }
        LocalDate effective = in.effectiveOn() == null ? today : in.effectiveOn();
        if (effective.isBefore(today.minusDays(30))) f.put("effectiveOn", "Choose a date no more than 30 days back.");
        if (effective.isAfter(today.plusYears(1))) f.put("effectiveOn", "Choose a date within the next year.");
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        AcademicLookup.Centre target = to.get();
        if (target.code().equalsIgnoreCase(s.getCentreCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "same_centre", s.displayName() + " is already at " + s.getCentreName() + ".");
        }
        tx.execute(t -> moves.findFirstByStudentIdAndStatus(studentId, CentreMove.Status.SCHEDULED)).ifPresent(p -> {
            throw new ApiException(HttpStatus.CONFLICT, "move_scheduled", "A move to " + p.getToCentreName() + " on " + SHORT.format(p.getEffectiveOn())
                    + " is already scheduled. Cancel it first.");
        });

        CentreMove m = new CentreMove();
        m.setId(UUID.randomUUID());
        m.setStudentId(studentId);
        m.setFromCentreId(s.getCentreId());
        m.setFromCentreCode(s.getCentreCode());
        m.setFromCentreName(s.getCentreName());
        m.setToCentreId(target.id());
        m.setToCentreCode(target.code());
        m.setToCentreName(target.name());
        m.setReason(reason);
        m.setEffectiveOn(effective);
        m.setStatus(CentreMove.Status.SCHEDULED);
        m.setRequestedBy(actor.userId());
        m.setRequestedByName(actor.name());
        m.setCreatedAt(clock.instant());

        // Tell the student and both coordinators (the emails name the effective date, so a later move is announced now).
        List<Notified> told = new ArrayList<>();
        List<String> notTold = new ArrayList<>();
        notifyAll(s, m, target, told, notTold);
        m.setNotified(json(told));
        m.setNotNotified(json(notTold));
        tx.executeWithoutResult(t -> {
            moves.save(m);
            if (effective.isAfter(today)) {
                audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_CENTRE_MOVE_SCHEDULED", "student", s.getId(),
                        actor.name() + " scheduled " + s.displayName() + "'s move from " + m.getFromCentreName() + " to " + m.getToCentreName()
                                + " on " + DAY.format(effective), Map.of("centre", m.getFromCentreName()),
                        Map.of("centre", m.getToCentreName(), "effectiveOn", effective.toString(), "reason", reason)));
            }
        });
        if (!effective.isAfter(today)) apply(m.getId(), actor);
        return view(tx.execute(t -> moves.findInTenant(m.getId()).orElseThrow()));
    }

    public Move cancel(CurrentUser cu, UUID studentId, UUID moveId) {
        AuditService.Actor actor = actors.actor(cu);
        return tx.execute(t -> {
            CentreMove m = moves.findInTenant(moveId).filter(x -> x.getStudentId().equals(studentId)).orElseThrow(ApiException::notFound);
            if (m.getStatus() != CentreMove.Status.SCHEDULED) {
                throw new ApiException(HttpStatus.CONFLICT, "not_scheduled", "Only a move that has not happened yet can be cancelled.");
            }
            m.setStatus(CentreMove.Status.CANCELLED);
            m.setCancelledAt(clock.instant());
            moves.save(m);
            audit.record(new AuditService.Entry(m.getTenantId(), actor, "STUDENT_CENTRE_MOVE_CANCELLED", "student", studentId,
                    actor.name() + " cancelled the move to " + m.getToCentreName(), null, null));
            return view(m);
        });
    }

    public Moves list(UUID studentId) {
        return tx.execute(t -> {
            students.findInTenant(studentId).orElseThrow(ApiException::notFound);
            return new Moves(moves.findByStudentIdOrderByCreatedAtDesc(studentId).stream().map(CentreMoveService::view).toList());
        });
    }

    // ---------------------------------------------------------------- applying

    /** Applies a move: the record's centre, the audit entry, then registration-service hands the open requests over. */
    void apply(UUID moveId, AuditService.Actor actor) {
        CentreMove done = tx.execute(t -> {
            CentreMove m = moves.findInTenant(moveId).orElseThrow();
            if (m.getStatus() != CentreMove.Status.SCHEDULED) return null;
            Student s = students.findInTenant(m.getStudentId()).orElseThrow();
            String old = s.getCentreName();
            s.setCentreId(m.getToCentreId());
            s.setCentreCode(m.getToCentreCode());
            s.setCentreName(m.getToCentreName());
            s.setCentreShortName(AcademicLookupService.shortName(m.getToCentreName()));
            s.setUpdatedAt(clock.instant());
            students.save(s);
            m.setStatus(CentreMove.Status.DONE);
            m.setAppliedAt(clock.instant());
            moves.save(m);
            audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_CENTRE_CHANGED", "student", s.getId(),
                    actor.name() + " moved " + s.displayName() + " from " + old + " to " + m.getToCentreName() + " (from "
                            + DAY.format(m.getEffectiveOn()) + "): " + m.getReason(),
                    Map.of("centre", old), Map.of("centre", m.getToCentreName(), "effectiveOn", m.getEffectiveOn().toString(), "reason", m.getReason())));
            return m;
        });
        if (done == null) return;
        try {
            CentreMovedResult r = registration.centreMoved(done.getStudentId(), new CentreMoved(done.getToCentreId(), done.getToCentreCode(), done.getToCentreName()));
            tx.executeWithoutResult(t -> moves.findInTenant(done.getId()).ifPresent(m -> {
                m.setRequestsMoved(r == null ? 0 : r.requestsMoved());
                moves.save(m);
            }));
        } catch (RuntimeException e) {
            // registration-service may not be running (it is optional for B1 deployments); the move itself stands.
            log.warn("centre move {}: could not hand the open registration requests over: {}", done.getId(), e.getMessage());
        }
    }

    /** Moves dated in the future are applied early on their day. Runs every hour (odel.student.centre-move-cron). */
    @Scheduled(cron = "${odel.student.centre-move-cron:0 5 * * * *}", zone = "Africa/Lagos")
    public void applyDue() {
        LocalDate today = LocalDate.now(clock.withZone(LAGOS));
        List<Map<String, Object>> due = jdbc.queryForList(
                "select id, tenant_id from student.centre_moves where status = 'SCHEDULED' and effective_on <= ?", Date.valueOf(today));
        for (Map<String, Object> row : due) {
            UUID tenant = (UUID) row.get("tenant_id");
            UUID id = (UUID) row.get("id");
            try {
                TenantContext.runAs(tenant, () -> apply(id, AuditService.Actor.SYSTEM));
            } catch (RuntimeException e) {
                log.warn("centre move {} not applied: {}", id, e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- emails

    private void notifyAll(Student s, CentreMove m, AcademicLookup.Centre target, List<Notified> told, List<String> notTold) {
        String effective = DAY.format(m.getEffectiveOn());
        NotificationDtos.TenantLook look = tenants.look();
        if (s.getEmail() != null) {
            Map<String, Object> v = new HashMap<>();
            v.put("firstName", s.getFirstName());
            v.put("fromCentre", m.getFromCentreName());
            v.put("toCentre", m.getToCentreName());
            v.put("effectiveOn", effective);
            v.put("reason", m.getReason());
            v.put("centrePhone", target.phone() == null ? "the ODeL office" : target.phone());
            safeSend(new NotificationDtos.Message(s.getUserId(), s.getEmail(), "student.centre_moved", v, "/", look));
            told.add(new Notified(s.displayName(), "Student", s.getEmail()));
        } else {
            notTold.add(s.displayName() + " has no email on record, so they were not emailed.");
        }
        Map<UUID, UUID> byCentre = new HashMap<>();
        String sessionName = null;
        try {
            Coordinators c = coordinators.current();
            if (c != null && c.items() != null) c.items().stream().filter(x -> x.userId() != null).forEach(x -> byCentre.put(x.centreId(), x.userId()));
            sessionName = c == null || c.session() == null ? null : c.session().name();
        } catch (RuntimeException e) {
            log.warn("centre move: could not read the coordinators: {}", e.getMessage());
        }
        coordinator(s, m, m.getFromCentreId(), m.getFromCentreName(), "leaves your centre", byCentre, sessionName, effective, look, told, notTold);
        coordinator(s, m, m.getToCentreId(), m.getToCentreName(), "joins your centre", byCentre, sessionName, effective, look, told, notTold);
    }

    private void coordinator(Student s, CentreMove m, UUID centreId, String centreName, String direction, Map<UUID, UUID> byCentre, String session,
                             String effective, NotificationDtos.TenantLook look, List<Notified> told, List<String> notTold) {
        UUID userId = centreId == null ? null : byCentre.get(centreId);
        if (userId == null) {
            notTold.add(centreName + " has no coordinator" + (session == null ? "" : " in " + session) + ": the ODeL office covers it.");
            return;
        }
        IdentityDtos.StaffView staff;
        try {
            staff = identity.staff(userId);
        } catch (RuntimeException e) {
            notTold.add("We could not reach the coordinator of " + centreName + ".");
            return;
        }
        Map<String, Object> v = new HashMap<>();
        v.put("fullName", staff.fullName());
        v.put("studentName", s.displayName());
        v.put("matricNumber", s.getMatricNumber());
        v.put("fromCentre", m.getFromCentreName());
        v.put("toCentre", m.getToCentreName());
        v.put("effectiveOn", effective);
        v.put("direction", direction);
        safeSend(new NotificationDtos.Message(userId, staff.email(), "staff.centre_move", v, "/staff/registration", look));
        told.add(new Notified(staff.fullName(), "Coordinator, " + centreName, staff.email()));
    }

    private void safeSend(NotificationDtos.Message msg) {
        try {
            notifications.send(msg);
        } catch (RuntimeException e) {
            log.warn("centre move email {} to {} not queued: {}", msg.templateKey(), msg.email(), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- views

    static Move view(CentreMove m) {
        return new Move(m.getId(), new CentreRef(m.getFromCentreId(), m.getFromCentreCode(), m.getFromCentreName()),
                new CentreRef(m.getToCentreId(), m.getToCentreCode(), m.getToCentreName()), m.getReason(), m.getEffectiveOn(), m.getStatus().name(),
                m.getRequestedByName(), m.getCreatedAt(), m.getAppliedAt(), m.getCancelledAt(),
                read(m.getNotified(), new TypeReference<List<Notified>>() {}), read(m.getNotNotified(), new TypeReference<List<String>>() {}),
                m.getRequestsMoved(), "Exams and tutorials move to " + m.getToCentreName() + " from " + SHORT.format(m.getEffectiveOn()));
    }

    private static <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null) return List.of();
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
