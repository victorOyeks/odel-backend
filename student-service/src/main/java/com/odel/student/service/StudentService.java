package com.odel.student.service;

import com.odel.common.ApiException;
import com.odel.common.PageResponse;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.student.client.IdentityClient;
import com.odel.student.dto.AcademicLookup;
import com.odel.student.dto.IdentityDtos;
import com.odel.student.dto.StudentDtos.*;
import com.odel.student.entity.Student;
import com.odel.student.entity.StudentImport;
import com.odel.student.entity.StudentStatusChange;
import com.odel.student.repository.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Students list and record (W-22, W-22b), add one (W-22c), edit, suspend and reinstate, and the student's own record (M-06). */
@Service
public class StudentService {
    private final StudentRepository students;
    private final StudentStatusChangeRepository history;
    private final StudentImportRepository imports;
    private final StudentBulkRepository bulk;
    private final IdentityClient identity;
    private final AcademicLookupService academic;
    private final WelcomeService welcome;
    private final ActorService actors;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StudentService(StudentRepository students, StudentStatusChangeRepository history, StudentImportRepository imports,
                          StudentBulkRepository bulk, IdentityClient identity, AcademicLookupService academic, WelcomeService welcome,
                          ActorService actors, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.students = students;
        this.history = history;
        this.imports = imports;
        this.bulk = bulk;
        this.identity = identity;
        this.academic = academic;
        this.welcome = welcome;
        this.actors = actors;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- list (W-22)

    public PageResponse<ListItem> list(String q, String programme, Integer level, String centre, String status, int page, int size) {
        Specification<Student> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                String matric = "%" + q.replaceAll("\\s+", "").toUpperCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(cb.concat(cb.concat(root.get("firstName"), " "), root.get("surname"))), like),
                        cb.like(cb.lower(cb.concat(cb.concat(root.get("surname"), " "), root.get("firstName"))), like),
                        cb.like(cb.lower(cb.coalesce(root.get("otherNames"), "")), like),
                        cb.like(root.get("matricNumber"), matric),
                        cb.like(cb.lower(cb.coalesce(root.get("email"), "")), like)));
            }
            if (programme != null && !programme.isBlank()) p.add(cb.equal(cb.upper(root.get("programmeCode")), programme.trim().toUpperCase(Locale.ROOT)));
            if (level != null) p.add(cb.equal(root.get("level"), level));
            if (centre != null && !centre.isBlank()) p.add(cb.equal(cb.upper(root.get("centreCode")), centre.trim().toUpperCase(Locale.ROOT)));
            if (status != null && !status.isBlank()) {
                String s = status.trim().toUpperCase(Locale.ROOT);
                if (s.equals("NOT_ACTIVATED")) p.add(root.get("status").in(Student.Status.IMPORTED, Student.Status.INVITED));
                else {
                    try {
                        p.add(cb.equal(root.get("status"), Student.Status.valueOf(s)));
                    } catch (IllegalArgumentException e) {
                        throw ApiException.badRequest("invalid", "Status is IMPORTED, INVITED, NOT_ACTIVATED, ACTIVE or SUSPENDED.");
                    }
                }
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        int sz = Math.max(1, Math.min(size, 100));
        return tx.execute(t -> PageResponse.of(students.findAll(spec, PageRequest.of(Math.max(0, page), sz, Sort.by("surname", "firstName", "matricNumber"))),
                this::item));
    }

    public Filters filters() {
        AcademicLookup l = academic.lookup();
        return new Filters(l.programmes().stream().map(p -> new FilterProgramme(p.code(), p.name(), p.levels())).toList(),
                l.centres().stream().map(c -> new FilterCentre(c.code(), c.name(), c.shortName())).toList(),
                List.of(new FilterStatus("NOT_ACTIVATED", "Not activated"), new FilterStatus("ACTIVE", "Active"), new FilterStatus("SUSPENDED", "Suspended")));
    }

    // ---------------------------------------------------------------- record (W-22b)

    public com.odel.student.dto.StudentDtos.Record record(UUID id) {
        Student s = tx.execute(t -> students.findInTenant(id).orElseThrow(ApiException::notFound));
        IdentityDtos.AccountView account = null;
        if (s.getUserId() != null) {
            try {
                account = identity.account(s.getUserId());
            } catch (ApiException e) {
                if (e.status() != HttpStatus.NOT_FOUND) throw e;
            }
        }
        IdentityDtos.AccountView a = account;
        return tx.execute(t -> {
            List<HistoryItem> h = history.findByStudentIdOrderByCreatedAt(s.getId()).stream()
                    .map(c -> new HistoryItem(c.getCreatedAt(), c.getFromStatus() == null ? null : c.getFromStatus().name(), c.getToStatus().name(),
                            c.getReason(), new Person(c.getActorName() == null ? "System" : c.getActorName()))).toList();
            ImportRef ref = s.getImportId() == null ? null : imports.findInTenant(s.getImportId()).map(i -> new ImportRef(i.getId(), i.getFileName())).orElse(null);
            return new com.odel.student.dto.StudentDtos.Record(s.getId(), s.getMatricNumber(), s.getSurname(), s.getFirstName(), s.getOtherNames(),
                    s.fullName(), s.getEmail(), s.getPhone(), s.getGender(), s.getDateOfBirth(), programme(s), s.getLevel(), s.getLevel() + "L", centre(s),
                    s.getEntrySession(), s.getStatus().name(), s.getStatus().label(),
                    new Account(s.getWelcomeSentAt(), s.getActivatedAt(), s.getConsentAt() == null ? null : new Consent(s.getConsentVersion(), s.getConsentAt()),
                            a == null ? null : a.lastSignInAt(), a == null ? null : a.lockedUntil()),
                    h, s.getCreatedAt(), ref);
        });
    }

    // ---------------------------------------------------------------- add one (W-22c)

    public com.odel.student.dto.StudentDtos.Record add(CurrentUser cu, AddRequest in) {
        UUID tenant = TenantContext.require();
        List<String> header = ImportValidator.COLUMNS;
        List<String> row = Arrays.asList(nz(in.matricNumber()), nz(in.surname()), nz(in.firstName()), nz(in.otherNames()), nz(in.programmeCode()),
                in.level() == null ? "" : in.level().toString(), nz(in.centreCode()), nz(in.email()), nz(in.phone()), "", "", "");
        ImportValidator.Result r;
        try {
            r = ImportValidator.validate(List.of(new CsvParser.Record(1, header), new CsvParser.Record(2, row)), academic.fresh(), bulk.existing(tenant),
                    checks -> new HashSet<>(identity.emailConflicts(checks.stream().map(c -> new IdentityDtos.EmailEntry(c.matricNumber(), c.email())).toList())
                            .stream().map(e -> e.email().toLowerCase(Locale.ROOT)).toList()));
        } catch (ImportValidator.FileProblem p) {
            throw ApiException.badRequest("invalid", p.getMessage());
        }
        ImportValidator.Row result = r.rows().get(0);
        if (result.outcome() == ImportValidator.Outcome.UPDATED || result.outcome() == ImportValidator.Outcome.UNCHANGED) {
            throw ApiException.fields(HttpStatus.CONFLICT, "matric_taken", result.data().matricNumber() + " is already a student here.",
                    Map.of("matricNumber", result.data().matricNumber() + " is already a student here."));
        }
        if (result.outcome() == ImportValidator.Outcome.ERROR) {
            Map<String, String> f = new LinkedHashMap<>();
            result.problems().stream().filter(p -> p.severity() == ImportValidator.Severity.ERROR)
                    .forEach(p -> f.putIfAbsent(field(p.field()), p.message() + "."));
            boolean emailTaken = result.problems().stream().anyMatch(p -> p.message().contains("already used by another account"));
            throw ApiException.fields(emailTaken ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST, emailTaken ? "email_taken" : "invalid",
                    f.values().iterator().next(), f);
        }
        AuditService.Actor actor = actors.actor(cu);
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        ImportValidator.StudentData d = result.data();
        tx.executeWithoutResult(t -> {
            bulk.insert(tenant, null, List.of(new StudentBulkRepository.NewStudent(id, d)), now);
            bulk.history(tenant, List.of(new StudentBulkRepository.StatusChange(id, null, "IMPORTED", "Added by hand")), actor.userId(), actor.name(), now);
            var created = identity.upsert(List.of(new IdentityDtos.Account(id, d.matricNumber(), d.firstName() + " " + d.surname(), d.email(), d.phone())));
            bulk.linkUsers(tenant, Map.of(id, created.get(0).userId()));
            audit.record(new AuditService.Entry(tenant, actor, "STUDENT_ADDED", "student", id,
                    actor.name() + " added " + d.firstName() + " " + d.surname() + " (" + d.matricNumber() + ")", null, Map.of("matricNumber", d.matricNumber())));
            if (Boolean.TRUE.equals(in.sendWelcome()) && d.email() != null) {
                welcome.sendOne(students.findInTenant(id).orElseThrow(), actor);
            }
        });
        return record(id);
    }

    // ---------------------------------------------------------------- edit (W-22b)

    public com.odel.student.dto.StudentDtos.Record update(CurrentUser cu, UUID id, UpdateRequest in) {
        AuditService.Actor actor = actors.actor(cu);
        Map<String, String> f = new LinkedHashMap<>();
        Student s = tx.execute(t -> students.findInTenant(id).orElseThrow(ApiException::notFound));
        Map<String, Object> before = snapshot(s), after;
        String oldCentre = s.getCentreName();

        if (in.surname() != null) { if (in.surname().isBlank()) f.put("surname", "Enter the surname."); else s.setSurname(clean(in.surname())); }
        if (in.firstName() != null) { if (in.firstName().isBlank()) f.put("firstName", "Enter the first name."); else s.setFirstName(clean(in.firstName())); }
        if (in.otherNames() != null) s.setOtherNames(in.otherNames().isBlank() ? null : clean(in.otherNames()));
        if (in.matricNumber() != null) {
            String m = in.matricNumber().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (!m.matches("^[A-Z0-9/\\-.]{3,40}$")) f.put("matricNumber", "Use letters, numbers and / - . (3 to 40 characters).");
            else if (!m.equals(s.getMatricNumber()) && tx.execute(t -> students.findByMatricNumber(m).isPresent())) {
                throw ApiException.fields(HttpStatus.CONFLICT, "matric_taken", m + " is already a student here.", Map.of("matricNumber", m + " is already a student here."));
            } else s.setMatricNumber(m);
        }
        if (in.email() != null) {
            String e = in.email().trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) s.setEmail(null);
            else if (!e.matches("^[a-z0-9._%+'-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$")) f.put("email", "Enter a valid email address.");
            else if (!e.equals(s.getEmail()) && tx.execute(t -> students.findFirstByEmailIgnoreCase(e).filter(o -> !o.getId().equals(id)).isPresent())) {
                throw ApiException.fields(HttpStatus.CONFLICT, "email_taken", e + " is already used by another student.", Map.of("email", e + " is already used by another student."));
            } else s.setEmail(e);
        }
        if (in.phone() != null) {
            if (in.phone().isBlank()) s.setPhone(null);
            else {
                String p = ImportValidator.phone(in.phone());
                if (p == null) f.put("phone", "Enter a Nigerian phone number, like 0803 412 4417.");
                else s.setPhone(p);
            }
        }
        if (in.programmeCode() != null) {
            var p = academic.programme(in.programmeCode());
            if (p.isEmpty()) f.put("programmeCode", "Choose a programme from Academic structure.");
            else {
                s.setProgrammeId(p.get().id());
                s.setProgrammeCode(p.get().code());
                s.setProgrammeName(p.get().name());
            }
        }
        if (in.level() != null) s.setLevel(in.level());
        var prog = academic.programme(s.getProgrammeCode());
        if ((in.level() != null || in.programmeCode() != null) && prog.isPresent() && prog.get().levels() != null && !prog.get().levels().isEmpty()
                && !prog.get().levels().contains(s.getLevel())) {
            f.put("level", "Level " + s.getLevel() + " is not offered for " + prog.get().name() + ".");
        }
        if (in.centreCode() != null) {
            var c = academic.centre(in.centreCode());
            if (c.isEmpty()) f.put("centreCode", "Choose a centre from Centres.");
            else {
                s.setCentreId(c.get().id());
                s.setCentreCode(c.get().code());
                s.setCentreName(c.get().name());
                s.setCentreShortName(c.get().shortName());
            }
        }
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        after = snapshot(s);
        if (after.equals(before)) return record(id);
        Instant now = clock.instant();
        s.setUpdatedAt(now);
        tx.executeWithoutResult(t -> {
            students.save(s);
            if (s.getUserId() != null) {
                identity.change(s.getUserId(), new IdentityDtos.ChangeRequest(s.getMatricNumber(), s.displayName(),
                        s.getEmail() == null ? "" : s.getEmail(), s.getPhone() == null ? "" : s.getPhone()));
            }
            Map<String, Object> oldV = new LinkedHashMap<>(), newV = new LinkedHashMap<>();
            before.forEach((k, v) -> { if (!Objects.equals(v, after.get(k))) { oldV.put(k, v); newV.put(k, after.get(k)); } });
            audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_UPDATED", "student", s.getId(),
                    actor.name() + " changed " + s.displayName() + "'s record (" + String.join(", ", newV.keySet()) + ")", oldV, newV));
            if (!Objects.equals(oldCentre, s.getCentreName())) {
                audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_CENTRE_CHANGED", "student", s.getId(),
                        actor.name() + " moved " + s.displayName() + " from " + oldCentre + " to " + s.getCentreName(),
                        Map.of("centre", oldCentre), Map.of("centre", s.getCentreName())));
            }
        });
        return record(id);
    }

    // ---------------------------------------------------------------- account actions

    public com.odel.student.dto.StudentDtos.Record suspend(CurrentUser cu, UUID id, String reason) {
        AuditService.Actor actor = actors.actor(cu);
        tx.executeWithoutResult(t -> {
            Student s = students.findInTenant(id).orElseThrow(ApiException::notFound);
            if (s.getStatus() == Student.Status.SUSPENDED) throw new ApiException(HttpStatus.CONFLICT, "already_suspended", "This student is already suspended.");
            String why = reason == null || reason.isBlank() ? "Suspended by the ODeL office" : "Suspended: " + reason.trim();
            change(s, Student.Status.SUSPENDED, why.length() > 300 ? why.substring(0, 300) : why, actor);
            if (s.getUserId() != null) identity.suspend(s.getUserId());
            audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_SUSPENDED", "student", s.getId(),
                    actor.name() + " suspended " + s.displayName() + " (" + s.getMatricNumber() + "); signed out everywhere", null,
                    reason == null ? null : Map.of("reason", reason)));
        });
        return record(id);
    }

    public com.odel.student.dto.StudentDtos.Record reinstate(CurrentUser cu, UUID id) {
        AuditService.Actor actor = actors.actor(cu);
        tx.executeWithoutResult(t -> {
            Student s = students.findInTenant(id).orElseThrow(ApiException::notFound);
            if (s.getStatus() != Student.Status.SUSPENDED) throw new ApiException(HttpStatus.CONFLICT, "not_suspended", "This student is not suspended.");
            Student.Status back = s.getStatusBeforeSuspension() != null ? s.getStatusBeforeSuspension()
                    : s.getActivatedAt() != null ? Student.Status.ACTIVE : Student.Status.IMPORTED;
            change(s, back, "Reinstated by the ODeL office", actor);
            s.setStatusBeforeSuspension(null);
            if (s.getUserId() != null) identity.reinstate(s.getUserId());
            audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_REINSTATED", "student", s.getId(),
                    actor.name() + " reinstated " + s.displayName() + " (" + s.getMatricNumber() + ")", null, null));
        });
        return record(id);
    }

    public void resendActivation(CurrentUser cu, UUID id) {
        AuditService.Actor actor = actors.actor(cu);
        tx.executeWithoutResult(t -> {
            Student s = students.findInTenant(id).orElseThrow(ApiException::notFound);
            if (s.getStatus() == Student.Status.ACTIVE) throw new ApiException(HttpStatus.CONFLICT, "already_active", "This student has already activated the account.");
            if (s.getStatus() == Student.Status.SUSPENDED) throw new ApiException(HttpStatus.CONFLICT, "suspended", "Reinstate this student first.");
            if (s.getEmail() == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "no_email", "This student has no email on record. Add one first.");
            welcome.sendOne(s, actor);
            audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_ACTIVATION_RESENT", "student", s.getId(),
                    actor.name() + " resent the activation message to " + s.displayName(), null, null));
        });
    }

    public void passwordReset(CurrentUser cu, UUID id) {
        AuditService.Actor actor = actors.actor(cu);
        Student s = tx.execute(t -> students.findInTenant(id).orElseThrow(ApiException::notFound));
        if (s.getStatus() != Student.Status.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, s.getStatus() == Student.Status.SUSPENDED ? "suspended" : "not_activated",
                    s.getStatus() == Student.Status.SUSPENDED ? "Reinstate this student first." : "This student has not activated the account yet. Resend the activation message instead.");
        }
        if (s.getEmail() == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "no_email", "This student has no email on record. Add one first.");
        identity.passwordReset(s.getUserId());
        audit.record(new AuditService.Entry(s.getTenantId(), actor, "STUDENT_PASSWORD_RESET_SENT", "student", s.getId(),
                actor.name() + " sent " + s.displayName() + " a password reset code", null, null));
    }

    // ---------------------------------------------------------------- the student's own record (M-06)

    public Profile profile(CurrentUser cu) {
        if (cu.activeRole() != Role.STUDENT) throw ApiException.forbidden();
        return tx.execute(t -> students.findByUserId(cu.userId()).map(this::profile).orElseThrow(ApiException::notFound));
    }

    public Profile updateProfile(CurrentUser cu, ProfileUpdate in) {
        if (cu.activeRole() != Role.STUDENT) throw ApiException.forbidden();
        Student s = tx.execute(t -> students.findByUserId(cu.userId()).orElseThrow(ApiException::notFound));
        String phone = in.phone() == null || in.phone().isBlank() ? null : ImportValidator.phone(in.phone());
        if (in.phone() != null && !in.phone().isBlank() && phone == null) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Enter a Nigerian phone number, like 0803 412 4417.",
                    Map.of("phone", "Enter a Nigerian phone number, like 0803 412 4417."));
        }
        tx.executeWithoutResult(t -> {
            String old = s.getPhone();
            s.setPhone(phone);
            s.setUpdatedAt(clock.instant());
            students.save(s);
            identity.change(s.getUserId(), new IdentityDtos.ChangeRequest(null, null, null, phone == null ? "" : phone));
            audit.record(new AuditService.Entry(s.getTenantId(), new AuditService.Actor(cu.userId(), cu.tenantId(), s.displayName(), "STUDENT"),
                    "STUDENT_UPDATED", "student", s.getId(), s.displayName() + " updated their phone number",
                    old == null ? null : Map.of("phone", old), phone == null ? null : Map.of("phone", phone)));
        });
        return tx.execute(t -> profile(students.findInTenant(s.getId()).orElseThrow()));
    }

    // ---------------------------------------------------------------- internal

    public Summary summary() {
        return tx.execute(t -> {
            long total = students.count();
            long active = students.countByStatus(Student.Status.ACTIVE);
            Instant last = imports.findFirstByStatusOrderByImportedAtDesc(StudentImport.Status.IMPORTED).map(StudentImport::getImportedAt).orElse(null);
            var w = bulk.welcomeCounts(TenantContext.require());
            List<CentreCount> byCentre = students.countByCentre().stream().map(r -> new CentreCount((UUID) r[0], ((Number) r[1]).longValue())).toList();
            return new Summary(total, active, last, new WelcomeCounts(w.sent(), w.failed(), w.notSent()), byCentre);
        });
    }

    public Contact contact(UUID userId) {
        return tx.execute(t -> students.findByUserId(userId).map(s -> {
            String phone = academic.lookup().centres().stream().filter(c -> c.code().equalsIgnoreCase(s.getCentreCode()))
                    .map(AcademicLookup.Centre::phone).filter(Objects::nonNull).findFirst().orElse(null);
            return new Contact(s.getId(), s.getFirstName(), new ContactCentre(s.getCentreName(), phone));
        }).orElseThrow(ApiException::notFound));
    }

    /** identity-service: the student activated. Safe to call twice. */
    public void activated(Activated in) {
        tx.executeWithoutResult(t -> {
            Student s = students.findByUserId(in.userId()).orElseThrow(ApiException::notFound);
            s.setActivatedAt(in.activatedAt());
            s.setConsentVersion(in.consentVersion());
            s.setConsentAt(in.consentAt());
            if (s.getStatus() != Student.Status.ACTIVE) {
                change(s, Student.Status.ACTIVE, "Activated the account and accepted the privacy notice",
                        new AuditService.Actor(in.userId(), s.getTenantId(), s.displayName(), "STUDENT"));
            }
            s.setUpdatedAt(clock.instant());
            students.save(s);
        });
    }

    // ---------------------------------------------------------------- helpers

    private void change(Student s, Student.Status to, String reason, AuditService.Actor actor) {
        StudentStatusChange c = new StudentStatusChange();
        c.setId(UUID.randomUUID());
        c.setStudentId(s.getId());
        c.setFromStatus(s.getStatus());
        c.setToStatus(to);
        c.setReason(reason);
        c.setActorUserId(actor == null ? null : actor.userId());
        c.setActorName(actor == null ? null : actor.name());
        c.setCreatedAt(clock.instant());
        history.save(c);
        if (to == Student.Status.SUSPENDED) s.setStatusBeforeSuspension(s.getStatus());
        s.setStatus(to);
        s.setUpdatedAt(clock.instant());
        students.save(s);
    }

    private ListItem item(Student s) {
        return new ListItem(s.getId(), s.getMatricNumber(), s.displayName(), programme(s), s.getLevel(), s.getLevel() + "L", centre(s), s.getEmail(),
                s.getStatus().name(), s.getStatus().label());
    }

    private Profile profile(Student s) {
        return new Profile(s.getId(), s.getMatricNumber(), s.displayName(), s.getFirstName(), s.initials(), programme(s), s.getLevel(), s.getLevel() + "L",
                centre(s), s.getEmail(), s.getPhone(), s.getStatus().name(), s.getConsentAt() == null ? null : new Consent(s.getConsentVersion(), s.getConsentAt()));
    }

    private static ProgrammeView programme(Student s) {
        return new ProgrammeView(s.getProgrammeCode(), s.getProgrammeName());
    }

    private static CentreView centre(Student s) {
        return new CentreView(s.getCentreCode(), s.getCentreName(), s.getCentreShortName());
    }

    private static Map<String, Object> snapshot(Student s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matricNumber", s.getMatricNumber());
        m.put("surname", s.getSurname());
        m.put("firstName", s.getFirstName());
        m.put("otherNames", s.getOtherNames());
        m.put("email", s.getEmail());
        m.put("phone", s.getPhone());
        m.put("programme", s.getProgrammeCode());
        m.put("level", s.getLevel());
        m.put("centre", s.getCentreCode());
        return m;
    }

    private static String field(String csvColumn) {
        return switch (csvColumn == null ? "" : csvColumn) {
            case "matric_number" -> "matricNumber";
            case "first_name" -> "firstName";
            case "other_names" -> "otherNames";
            case "programme_code" -> "programmeCode";
            case "study_centre" -> "centreCode";
            default -> csvColumn;
        };
    }

    private static String clean(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
