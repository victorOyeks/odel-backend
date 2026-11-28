package com.odel.registration.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.files.Csv;
import com.odel.common.files.StorageService;
import com.odel.common.files.StoredFiles;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.registration.client.AcademicClient;
import com.odel.registration.client.StudentsClient;
import com.odel.registration.dto.PeerDtos.CourseInfo;
import com.odel.registration.dto.PeerDtos.StudentRef;
import com.odel.registration.dto.RequestDtos.UploadLink;
import com.odel.registration.dto.RequestDtos.UploadRequest;
import com.odel.registration.dto.StaffDtos.*;
import com.odel.registration.entity.CarryOver;
import com.odel.registration.repository.CarryOverRepository;
import com.odel.registration.repository.RegistrationCourseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Carry-overs until B6's results produce them: the ODeL admin records them one by one or by CSV; open ones are pinned on the
 * student's registration in the semester the course runs.
 */
@Service
public class CarryOverService {
    public static final List<String> COLUMNS = List.of("matric_number", "course_code", "from_session");

    private final CarryOverRepository carryOvers;
    private final RegistrationCourseRepository regCourses;
    private final StudentsClient students;
    private final AcademicClient academic;
    private final StorageService storage;
    private final StoredFiles files;
    private final ActorService actors;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CarryOverService(CarryOverRepository carryOvers, RegistrationCourseRepository regCourses, StudentsClient students, AcademicClient academic,
                            StorageService storage, StoredFiles files, ActorService actors, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.carryOvers = carryOvers;
        this.regCourses = regCourses;
        this.students = students;
        this.academic = academic;
        this.storage = storage;
        this.files = files;
        this.actors = actors;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public CarryOvers list(CurrentUser cu, UUID studentId) {
        Access.require(cu, Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        if (studentId == null) throw ApiException.badRequest("invalid", "Choose a student.");
        students.byId(studentId);
        return new CarryOvers(tx.execute(t -> carryOvers.forStudent(studentId)).stream().map(Views::carryOver).toList());
    }

    public CarryOverView add(CurrentUser cu, CarryOverIn in) {
        Access.require(cu, Role.ODEL_ADMIN);
        AuditService.Actor actor = actors.actor(cu);
        if (in.studentId() == null) throw ApiException.badRequest("invalid", "Choose a student.");
        StudentRef s = students.byId(in.studentId());
        String code = in.courseCode() == null ? "" : in.courseCode().trim();
        List<CourseInfo> found = code.isEmpty() ? List.of() : academic.courseByCode(code);
        if (found.isEmpty()) {
            String msg = code.isEmpty() ? "Enter the course code." : code.toUpperCase(Locale.ROOT) + " is not in the catalogue.";
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", msg, Map.of("courseCode", msg));
        }
        CourseInfo c = found.get(0);
        if (tx.execute(t -> carryOvers.open(s.id(), c.id()).isPresent())) {
            throw new ApiException(HttpStatus.CONFLICT, "already_open", c.code() + " is already a carry-over for " + s.fullName() + ".");
        }
        CarryOver co = build(s, c, in.fromSession(), CarryOver.Source.ADMIN, actor.name());
        tx.executeWithoutResult(t -> {
            carryOvers.save(co);
            audit.record(new AuditService.Entry(TenantContext.require(), actor, "CARRY_OVER_ADDED", "carry_over", co.getId(),
                    actor.name() + " recorded " + c.code() + " as a carry-over for " + s.fullName() + " (" + s.matricNumber() + ")", null, null));
        });
        return Views.carryOver(co);
    }

    public void remove(CurrentUser cu, UUID id) {
        Access.require(cu, Role.ODEL_ADMIN);
        AuditService.Actor actor = actors.actor(cu);
        tx.executeWithoutResult(t -> {
            CarryOver co = carryOvers.findInTenant(id).orElseThrow(ApiException::notFound);
            if (regCourses.usesCarryOver(co.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "in_use", co.getCourseCode() + " is already on the student's registration, so it cannot be removed.");
            }
            carryOvers.delete(co);
            audit.record(new AuditService.Entry(TenantContext.require(), actor, "CARRY_OVER_REMOVED", "carry_over", co.getId(),
                    actor.name() + " removed the carry-over " + co.getCourseCode(), null, null));
        });
    }

    public UploadLink uploadLink(CurrentUser cu, UploadRequest in) {
        Access.require(cu, Role.ODEL_ADMIN);
        String name = in.fileName() == null ? "" : in.fileName().trim();
        Map<String, String> f = new LinkedHashMap<>();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) f.put("fileName", "Upload a .csv file.");
        if (in.size() != null && in.size() > 5L * 1024 * 1024) f.put("size", "The file is over 5 MB. Split it into smaller files.");
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        String key = "imports/" + TenantContext.require() + "/carry-overs-" + UUID.randomUUID() + ".csv";
        Duration ttl = Duration.ofMinutes(15);
        return new UploadLink(key, storage.presignPut(key, ttl), "PUT", Map.of("Content-Type", "text/csv"), clock.instant().plus(ttl));
    }

    public ImportResult importFile(CurrentUser cu, ImportIn in) {
        Access.require(cu, Role.ODEL_ADMIN);
        AuditService.Actor actor = actors.actor(cu);
        String key = in.key() == null ? "" : in.key().trim();
        if (!key.startsWith("imports/" + TenantContext.require() + "/carry-overs-") || key.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_missing", "We could not find the uploaded file. Upload it again.");
        }
        List<List<String>> parsed = Csv.parse(files.read(key, 5L * 1024 * 1024));
        if (!Csv.headerMatches(parsed, COLUMNS)) {
            throw ApiException.badRequest("bad_header", "The columns do not match the template: download the template and copy your rows into it.");
        }
        List<Csv.Row> rows = Csv.dataRows(parsed);
        if (rows.isEmpty()) throw ApiException.badRequest("empty_file", "The file has no rows under the header.");
        Map<String, StudentRef> byMatric = students.roster().stream().collect(Collectors.toMap(StudentRef::matricNumber, s -> s, (a, b) -> a));
        Map<String, CourseInfo> courseCache = new HashMap<>();
        List<ImportProblem> problems = new ArrayList<>();
        List<CarryOver> toAdd = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Csv.Row r : rows) {
            String matric = r.get(0).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            String code = r.get(1).trim();
            String session = r.get(2).trim();
            StudentRef s = byMatric.get(matric);
            if (s == null) { problems.add(new ImportProblem(r.number(), "Matric number " + (matric.isEmpty() ? "(empty)" : matric) + " not found")); continue; }
            String norm = code.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            CourseInfo c = courseCache.computeIfAbsent(norm, k -> k.isEmpty() ? null : academic.courseByCode(k).stream().findFirst().orElse(null));
            if (c == null) { problems.add(new ImportProblem(r.number(), "Course " + (code.isEmpty() ? "(empty)" : code) + " is not in the catalogue")); continue; }
            if (!session.isEmpty() && !session.matches("\\d{4}/\\d{4}")) { problems.add(new ImportProblem(r.number(), "Write the session as 2025/2026")); continue; }
            if (!seen.add(s.id() + "/" + c.id())) continue;
            if (tx.execute(t -> carryOvers.open(s.id(), c.id()).isPresent())) continue;
            toAdd.add(build(s, c, session.isEmpty() ? null : session, CarryOver.Source.IMPORT, actor.name()));
        }
        tx.executeWithoutResult(t -> {
            carryOvers.saveAll(toAdd);
            audit.record(new AuditService.Entry(TenantContext.require(), actor, "CARRY_OVERS_IMPORTED", "carry_over", null,
                    actor.name() + " imported " + toAdd.size() + " carry-overs (" + problems.size() + " rows skipped)", null,
                    Map.of("fileName", String.valueOf(in.fileName()))));
        });
        return new ImportResult(rows.size(), toAdd.size(), problems.size(), problems);
    }

    public static String template() {
        return String.join(",", COLUMNS) + "\nWU/ODL/24/0153,CSC 103,2025/2026\n";
    }

    private CarryOver build(StudentRef s, CourseInfo c, String fromSession, CarryOver.Source source, String by) {
        CarryOver co = new CarryOver();
        co.setId(UUID.randomUUID());
        co.setStudentId(s.id());
        co.setCourseId(c.id());
        co.setCourseCode(c.code());
        co.setCourseTitle(c.title());
        co.setUnits(c.units());
        co.setSemester(c.semester());
        co.setFromSession(fromSession == null || fromSession.isBlank() ? null : fromSession.trim());
        co.setSource(source);
        co.setStatus(CarryOver.Status.OPEN);
        co.setCreatedAt(clock.instant());
        co.setCreatedByName(by);
        return co;
    }
}
