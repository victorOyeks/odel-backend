package com.odel.student.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.files.StorageService;
import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import com.odel.student.client.IdentityClient;
import com.odel.student.dto.IdentityDtos;
import com.odel.student.dto.ImportDtos.*;
import com.odel.student.entity.StudentImport;
import com.odel.student.repository.ImportProblemRepository;
import com.odel.student.repository.StudentBulkRepository;
import com.odel.student.repository.StudentImportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * W-18 → W-19 → confirm: the browser puts the portal CSV straight into storage, the check reads it and reports row by row
 * (saving no student), and the confirm creates the good rows' students and accounts and records the import.
 */
@Service
public class StudentImportService {
    private static final Duration CHECK_VALID_FOR = Duration.ofHours(24);
    private static final NumberFormat N = NumberFormat.getIntegerInstance(Locale.UK);

    private final StorageService storage;
    private final ImportFileReader reader;
    private final AcademicLookupService academic;
    private final IdentityClient identity;
    private final StudentBulkRepository bulk;
    private final ImportProblemRepository problems;
    private final StudentImportRepository imports;
    private final ActorService actors;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StudentImportService(StorageService storage, ImportFileReader reader, AcademicLookupService academic, IdentityClient identity,
                                StudentBulkRepository bulk, ImportProblemRepository problems, StudentImportRepository imports,
                                ActorService actors, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.storage = storage;
        this.reader = reader;
        this.academic = academic;
        this.identity = identity;
        this.bulk = bulk;
        this.problems = problems;
        this.imports = imports;
        this.actors = actors;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- upload ----

    public UploadLink uploadLink(UploadRequest in) {
        Map<String, String> f = new LinkedHashMap<>();
        String name = in.fileName() == null ? "" : in.fileName().trim();
        if (name.isEmpty()) f.put("fileName", "Choose a file.");
        else if (name.toLowerCase(Locale.ROOT).endsWith(".xlsx") || name.toLowerCase(Locale.ROOT).endsWith(".xls")) {
            f.put("fileName", "Save the Excel file as CSV (File → Save as → CSV UTF-8) and upload that.");
        } else if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) f.put("fileName", "Upload a .csv file.");
        if (in.size() != null && in.size() > ImportFileReader.MAX_BYTES) f.put("size", "The file is over 10 MB. Split it into smaller files.");
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        String key = "imports/" + TenantContext.require() + "/" + UUID.randomUUID() + ".csv";
        Duration ttl = Duration.ofMinutes(15);
        return new UploadLink(key, storage.presignPut(key, ttl), "PUT", Map.of("Content-Type", "text/csv"), clock.instant().plus(ttl));
    }

    // ---- check (W-19) ----

    public Report validate(CurrentUser cu, ValidateRequest in) {
        UUID tenant = TenantContext.require();
        String key = in.key() == null ? "" : in.key().trim();
        if (!key.startsWith("imports/" + tenant + "/") || key.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_missing", "We could not find the uploaded file. Upload it again.");
        }
        ImportValidator.Result result = check(key);
        String fileName = in.fileName() == null || in.fileName().isBlank() ? "students.csv" : in.fileName().trim();
        var staff = actors.staff(cu);
        StudentImport imp = new StudentImport();
        imp.setId(UUID.randomUUID());
        imp.setFileKey(key);
        imp.setFileName(fileName.length() > 300 ? fileName.substring(0, 300) : fileName);
        imp.setStatus(StudentImport.Status.CHECKED);
        counts(imp, result);
        imp.setCheckedAt(clock.instant());
        imp.setCheckedBy(cu.userId());
        imp.setCheckedByName(staff.fullName());
        tx.executeWithoutResult(s -> {
            imports.save(imp);
            problems.replace(tenant, imp.getId(), result.problems());
        });
        return report(imp);
    }

    public Report report(UUID importId) {
        return report(find(importId));
    }

    public com.odel.common.PageResponse<ProblemView> problems(UUID importId, String severity, int page, int size) {
        StudentImport imp = find(importId);
        String sev = severity == null || severity.isBlank() ? null : severity.trim().toUpperCase(Locale.ROOT);
        if (sev != null && !sev.equals("ERROR") && !sev.equals("WARNING")) throw ApiException.badRequest("invalid", "Severity is ERROR or WARNING.");
        int sz = Math.max(1, Math.min(size, 200));
        UUID t = TenantContext.require();
        return new com.odel.common.PageResponse<>(problems.page(t, imp.getId(), sev, page * sz, sz), page, sz, problems.count(t, imp.getId(), sev));
    }

    /** "Download problem rows (CSV)": the rows as the file had them, with the problem and its type. */
    public String problemsCsv(UUID importId) {
        StudentImport imp = find(importId);
        UUID t = TenantContext.require();
        List<ProblemView> list = problems.page(t, imp.getId(), null, 0, 100_000);
        List<CsvParser.Record> records;
        try {
            records = reader.read(imp.getFileKey());
        } catch (ApiException e) {
            records = List.of();
        }
        Map<Integer, List<String>> raw = records.stream().collect(Collectors.toMap(CsvParser.Record::line, CsvParser.Record::fields, (a, b) -> a));
        List<String> header = records.isEmpty() ? List.of("matric_number", "name") : records.get(0).fields();
        StringBuilder sb = new StringBuilder();
        sb.append("row,type,problem,").append(header.stream().map(CsvParser::escape).collect(Collectors.joining(","))).append("\r\n");
        for (ProblemView p : list.stream().sorted(Comparator.comparingInt(ProblemView::row)).toList()) {
            List<String> r = raw.getOrDefault(p.row(), records.isEmpty() ? List.of(nz(p.matricNumber()), nz(p.name())) : List.of());
            sb.append(p.row()).append(',').append(p.severity().equals("ERROR") ? "Error" : "Warning").append(',').append(CsvParser.escape(p.message()))
                    .append(',').append(r.stream().map(CsvParser::escape).collect(Collectors.joining(","))).append("\r\n");
        }
        return sb.toString();
    }

    // ---- confirm ----

    public ConfirmResult confirm(CurrentUser cu, UUID importId) {
        UUID tenant = TenantContext.require();
        StudentImport imp = find(importId);
        if (imp.getStatus() == StudentImport.Status.IMPORTED) {
            throw new ApiException(HttpStatus.CONFLICT, "already_imported", "This file has already been imported. See Import history.");
        }
        if (imp.getCheckedAt().plus(CHECK_VALID_FOR).isBefore(clock.instant())) {
            throw new ApiException(HttpStatus.GONE, "expired", "This check is more than a day old. Check the file again.");
        }
        ImportValidator.Result result = check(imp.getFileKey());
        AuditService.Actor actor = actors.actor(cu);
        Instant now = clock.instant();

        List<StudentBulkRepository.NewStudent> added = new ArrayList<>();
        Map<UUID, ImportValidator.StudentData> changed = new LinkedHashMap<>();
        List<IdentityDtos.Account> accounts = new ArrayList<>();
        for (ImportValidator.Row row : result.rows()) {
            switch (row.outcome()) {
                case NEW -> {
                    UUID id = UUID.randomUUID();
                    added.add(new StudentBulkRepository.NewStudent(id, row.data()));
                    accounts.add(account(id, row.data()));
                }
                case UPDATED -> {
                    changed.put(row.existingId(), row.data());
                    accounts.add(account(row.existingId(), row.data()));
                }
                default -> { }
            }
        }
        tx.executeWithoutResult(s -> {
            bulk.insert(tenant, imp.getId(), added, now);
            bulk.update(tenant, changed, now);
            bulk.history(tenant, added.stream().map(n -> new StudentBulkRepository.StatusChange(n.id(), null, "IMPORTED",
                    "Imported from " + imp.getFileName())).toList(), actor.userId(), actor.name(), now);
            Map<UUID, UUID> links = new HashMap<>();
            for (IdentityDtos.Created c : identity.upsert(accounts)) links.put(c.studentId(), c.userId());
            bulk.linkUsers(tenant, links);
            counts(imp, result);
            imp.setStatus(StudentImport.Status.IMPORTED);
            imp.setImportedAt(now);
            imp.setImportedBy(actor.userId());
            imp.setImportedByName(actor.name());
            imports.save(imp);
            problems.replace(tenant, imp.getId(), result.problems());
            audit.record(new AuditService.Entry(tenant, actor, "STUDENTS_IMPORTED", "import", imp.getId(),
                    "Imported " + imp.getFileName() + ": " + N.format(result.added()) + " added, " + N.format(result.updated()) + " updated, "
                            + N.format(result.errors()) + " rows with errors skipped", null,
                    Map.of("added", result.added(), "updated", result.updated(), "unchanged", result.unchanged(), "errors", result.errors(),
                            "warnings", result.warnings(), "fileName", imp.getFileName())));
        });
        return new ConfirmResult(imp.getId(), "IMPORTED", result.added(), result.updated(), result.unchanged(), result.errors(), result.warnings(),
                now, new Person(actor.userId(), actor.name()));
    }

    // ---- history (W-18b) ----

    public com.odel.common.PageResponse<HistoryItem> history(int page, int size) {
        var p = imports.findByStatusOrderByImportedAtDesc(StudentImport.Status.IMPORTED, PageRequest.of(page, Math.max(1, Math.min(size, 100))));
        return com.odel.common.PageResponse.of(p, i -> new HistoryItem(i.getId(), i.getFileName(), i.getImportedAt(),
                new Person(i.getImportedBy(), i.getImportedByName()), i.getTotalRows(), i.getNewCount(), i.getUpdatedCount(), i.getUnchangedCount(),
                i.getErrorCount(), i.getWarningCount()));
    }

    // ---- helpers ----

    private ImportValidator.Result check(String key) {
        List<CsvParser.Record> records = reader.read(key);
        UUID tenant = TenantContext.require();
        try {
            return ImportValidator.validate(records, academic.fresh(), bulk.existing(tenant), checks -> {
                List<IdentityDtos.EmailEntry> conflicts = identity.emailConflicts(checks.stream()
                        .map(c -> new IdentityDtos.EmailEntry(c.matricNumber(), c.email())).toList());
                return conflicts.stream().map(c -> c.email().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            });
        } catch (ImportValidator.FileProblem p) {
            throw new ApiException(HttpStatus.BAD_REQUEST, p.code, p.getMessage(), p.fields, null);
        }
    }

    private static IdentityDtos.Account account(UUID studentId, ImportValidator.StudentData d) {
        return new IdentityDtos.Account(studentId, d.matricNumber(), d.firstName() + " " + d.surname(), d.email(), d.phone());
    }

    private static void counts(StudentImport imp, ImportValidator.Result r) {
        imp.setTotalRows(r.total());
        imp.setReadyCount(r.ready());
        imp.setNewCount(r.added());
        imp.setUpdatedCount(r.updated());
        imp.setUnchangedCount(r.unchanged());
        imp.setErrorCount(r.errors());
        imp.setWarningCount(r.warnings());
    }

    private Report report(StudentImport imp) {
        UUID t = TenantContext.require();
        List<ProblemView> first = problems.page(t, imp.getId(), null, 0, 50);
        return new Report(imp.getId(), imp.getFileName(), imp.getStatus().name(), imp.getTotalRows(),
                new Ready(imp.getReadyCount(), imp.getNewCount(), imp.getUpdatedCount(), imp.getUnchangedCount()),
                new Count(imp.getErrorCount()), new Count(imp.getWarningCount()), first, first.size(), imp.getCheckedAt(), imp.getImportedAt(),
                imp.getImportedBy() == null ? null : new Person(imp.getImportedBy(), imp.getImportedByName()));
    }

    private StudentImport find(UUID id) {
        return tx.execute(s -> imports.findInTenant(id).orElseThrow(ApiException::notFound));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
