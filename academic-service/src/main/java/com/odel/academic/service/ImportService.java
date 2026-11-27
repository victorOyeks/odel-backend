package com.odel.academic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.academic.dto.ImportDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.files.Csv;
import com.odel.common.files.StorageService;
import com.odel.common.files.StoredFiles;
import com.odel.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * Structure (W-12c) and catalogue (W-13c) imports from CSV. The browser uploads the file straight to storage; the check
 * reads it, reports every problem row and saves nothing; confirm saves the ready rows (checked again against what exists then).
 */
@Service
public class ImportService {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final List<String> STRUCTURE_COLUMNS = List.of("faculty_name", "faculty_code", "department_name", "department_code",
            "programme_name", "programme_code", "degree", "duration_years", "entry_levels");
    public static final List<String> CATALOGUE_COLUMNS = List.of("code", "title", "units", "level", "semester", "programme_code", "kind");

    private final ImportJobRepository imports;
    private final FacultyRepository faculties;
    private final DepartmentRepository departments;
    private final ProgrammeRepository programmes;
    private final CourseRepository courses;
    private final CourseProgrammeRepository offers;
    private final StorageService storage;
    private final StoredFiles files;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public ImportService(ImportJobRepository imports, FacultyRepository faculties, DepartmentRepository departments, ProgrammeRepository programmes,
                         CourseRepository courses, CourseProgrammeRepository offers, StorageService storage, StoredFiles files,
                         AuditService audit, ObjectMapper json, Clock clock) {
        this.imports = imports;
        this.faculties = faculties;
        this.departments = departments;
        this.programmes = programmes;
        this.courses = courses;
        this.offers = offers;
        this.storage = storage;
        this.files = files;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---- upload ----

    public UploadResponse upload(UploadRequest in) {
        String name = in.fileName() == null ? "" : in.fileName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv")) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Upload a CSV file.", Map.of("file", "Save the sheet as CSV (Comma delimited) and upload that."));
        }
        if (in.size() == null || in.size() <= 0 || in.size() > MAX_BYTES) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The file must be under 5 MB.", Map.of("file", "Use a file under 5 MB."));
        }
        String key = "imports/" + TenantContext.require() + "/" + UUID.randomUUID() + ".csv";
        Duration ttl = Duration.ofMinutes(15);
        return new UploadResponse(key, storage.presignPut(key, ttl), "PUT", Map.of("Content-Type", "text/csv"), clock.instant().plus(ttl));
    }

    // ---- check ----

    @Transactional
    public ImportView check(ImportJob.Kind kind, CheckRequest in, AuditService.Actor actor) {
        if (in.key() == null || !in.key().startsWith("imports/" + TenantContext.require() + "/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "upload_missing", "We could not find the uploaded file. Upload it again.");
        }
        List<List<String>> parsed = Csv.parse(files.read(in.key(), MAX_BYTES));
        List<String> columns = kind == ImportJob.Kind.STRUCTURE ? STRUCTURE_COLUMNS : CATALOGUE_COLUMNS;
        if (!Csv.headerMatches(parsed, columns)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_header",
                    "The columns do not match the template: download the template and copy your rows into it. Expected: " + String.join(", ", columns) + ".");
        }
        List<StoredRow> rows = Csv.dataRows(parsed).stream().map(r -> new StoredRow(r.number(), r.cells())).toList();
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "empty_file", "The file has no rows under the header.");
        Map<Integer, List<String>> problems = kind == ImportJob.Kind.STRUCTURE ? checkStructure(rows) : checkCatalogue(rows);

        ImportJob job = new ImportJob();
        job.setId(UUID.randomUUID());
        job.setKind(kind);
        job.setFileName(in.fileName() == null || in.fileName().isBlank() ? "import.csv" : in.fileName().trim());
        job.setFileKey(in.key());
        job.setStatus(ImportJob.Status.CHECKED);
        job.setTotalRows(rows.size());
        job.setProblemRows(problems.size());
        job.setReadyRows(rows.size() - problems.size());
        job.setProblems(write(problemList(problems)));
        job.setPayload(write(rows.stream().filter(r -> !problems.containsKey(r.row())).toList()));
        StringBuilder csv = new StringBuilder(Csv.line(new ArrayList<>(columns) {{ add("problem"); }}));
        for (StoredRow r : rows) {
            if (!problems.containsKey(r.row())) continue;
            List<String> cells = new ArrayList<>(r.cells());
            while (cells.size() < columns.size()) cells.add("");
            cells = new ArrayList<>(cells.subList(0, columns.size()));
            cells.add("Row " + r.row() + ": " + String.join("; ", problems.get(r.row())));
            csv.append(Csv.line(cells));
        }
        job.setProblemCsv(csv.toString());
        job.setCreatedAt(clock.instant());
        job.setCreatedBy(actor.userId());
        imports.saveAndFlush(job);
        return view(job);
    }

    @Transactional(readOnly = true)
    public ImportView get(UUID id) {
        return view(imports.findById(id).orElseThrow(ApiException::notFound));
    }

    @Transactional(readOnly = true)
    public String problemCsv(UUID id) {
        return imports.findById(id).orElseThrow(ApiException::notFound).getProblemCsv();
    }

    @Transactional
    public void cancel(UUID id) {
        ImportJob job = imports.findById(id).orElseThrow(ApiException::notFound);
        if (job.getStatus() == ImportJob.Status.CHECKED) {
            job.setStatus(ImportJob.Status.CANCELLED);
            imports.save(job);
        }
    }

    // ---- confirm ----

    @Transactional
    public ImportView confirm(UUID id, AuditService.Actor actor) {
        ImportJob job = imports.findById(id).orElseThrow(ApiException::notFound);
        if (job.getStatus() != ImportJob.Status.CHECKED) {
            throw new ApiException(HttpStatus.CONFLICT, "already_done", job.getStatus() == ImportJob.Status.IMPORTED
                    ? "This file has already been imported." : "This import was cancelled. Upload the file again.");
        }
        List<StoredRow> rows = read(job.getPayload(), new TypeReference<>() {});
        // Checked again: something may have been added on the screens since the check.
        Map<Integer, List<String>> now = job.getKind() == ImportJob.Kind.STRUCTURE ? checkStructure(rows) : checkCatalogue(rows);
        List<StoredRow> ready = rows.stream().filter(r -> !now.containsKey(r.row())).toList();
        Map<String, Integer> counts = job.getKind() == ImportJob.Kind.STRUCTURE ? saveStructure(ready) : saveCatalogue(ready);
        if (!now.isEmpty()) counts.put("skipped", now.size());
        job.setStatus(ImportJob.Status.IMPORTED);
        job.setConfirmedAt(clock.instant());
        job.setImported(write(counts));
        imports.saveAndFlush(job);
        String what = job.getKind() == ImportJob.Kind.STRUCTURE
                ? counts.get("faculties") + " faculties, " + counts.get("departments") + " departments and " + counts.get("programmes") + " programmes"
                : counts.get("courses") + " courses";
        audit.record(new AuditService.Entry(TenantContext.require(), actor, job.getKind() == ImportJob.Kind.STRUCTURE ? "IMPORT_STRUCTURE" : "IMPORT_CATALOGUE",
                "import", job.getId(), "Imported " + what + " from " + job.getFileName() + (job.getProblemRows() > 0 ? " (" + job.getProblemRows() + " rows left out)" : ""),
                null, counts));
        return view(job);
    }

    // ---- structure rows ----

    private record Unit(String code, String name, String parentCode, int row) {}

    Map<Integer, List<String>> checkStructure(List<StoredRow> rows) {
        Map<Integer, List<String>> p = new TreeMap<>();
        Map<String, Unit> fileFaculties = new HashMap<>(), fileDepartments = new HashMap<>();
        Map<String, List<Integer>> programmeCodes = new HashMap<>();
        for (StoredRow r : rows) programmeCodes.computeIfAbsent(Codes.unit(cell(r, 5)), k -> new ArrayList<>()).add(r.row());
        for (StoredRow r : rows) {
            List<String> problems = new ArrayList<>();
            String fName = cell(r, 0), fCode = Codes.unit(cell(r, 1)), dName = cell(r, 2), dCode = Codes.unit(cell(r, 3));
            String pName = cell(r, 4), pCode = Codes.unit(cell(r, 5)), degree = cell(r, 6);
            Integer duration = integer(cell(r, 7));
            List<Integer> entry = levels(cell(r, 8));
            if (fName.isEmpty() || fCode.isEmpty()) problems.add("Give the faculty's name and code");
            else if (!fCode.matches(Codes.UNIT)) problems.add("Faculty code " + fCode + " should be letters and numbers only");
            if (dName.isEmpty() || dCode.isEmpty()) problems.add("Give the department's name and code");
            else if (!dCode.matches(Codes.UNIT)) problems.add("Department code " + dCode + " should be letters and numbers only");
            if (!cell(r, 7).isEmpty() && duration == null) problems.add("Duration \"" + cell(r, 7) + "\" is not a number of years");
            if (entry == null) problems.add("Entry levels \"" + cell(r, 8) + "\" should look like 100 or 100;200");
            Map<String, String> pf = StructureService.checkProgramme(pName, pCode, degree, duration, entry);
            pf.values().forEach(m -> problems.add(m.replace(".", "")));
            if (problems.isEmpty()) {
                // Faculty: the same code must always mean the same faculty, here and in the file.
                Optional<Faculty> fDb = faculties.findByCode(fCode);
                if (fDb.isPresent() && !fDb.get().getName().equalsIgnoreCase(fName)) problems.add("Faculty code " + fCode + " is " + fDb.get().getName() + " here, not " + fName);
                else if (fDb.isEmpty() && faculties.findByName(fName).isPresent()) problems.add(fName + " already exists with the code " + faculties.findByName(fName).get().getCode());
                Unit fFile = fileFaculties.putIfAbsent(fCode, new Unit(fCode, fName, null, r.row()));
                if (fFile != null && !fFile.name().equalsIgnoreCase(fName)) problems.add("Faculty code " + fCode + " is " + fFile.name() + " on row " + fFile.row());
                // Department: one faculty, one name.
                Optional<Department> dDb = departments.findByCode(dCode);
                if (dDb.isPresent()) {
                    Faculty owner = faculties.findById(dDb.get().getFacultyId()).orElseThrow();
                    if (!dDb.get().getName().equalsIgnoreCase(dName)) problems.add("Department code " + dCode + " is " + dDb.get().getName() + " here, not " + dName);
                    else if (!owner.getCode().equalsIgnoreCase(fCode)) problems.add(dDb.get().getName() + " belongs to " + owner.getName() + ", not " + fName);
                }
                Unit dFile = fileDepartments.putIfAbsent(dCode, new Unit(dCode, dName, fCode, r.row()));
                if (dFile != null && (!dFile.name().equalsIgnoreCase(dName) || !dFile.parentCode().equals(fCode))) {
                    problems.add("Department code " + dCode + " is used differently on row " + dFile.row());
                }
                // Programme: new, and once in the file.
                List<Integer> same = programmeCodes.get(pCode);
                Optional<Programme> pDb = programmes.findByCode(pCode);
                if (pDb.isPresent()) problems.add("Programme code " + pCode + " already exists (" + pDb.get().getName() + ")");
                else if (same.size() > 1) problems.add("Programme code " + pCode + " appears more than once in the file (rows " + join(same) + ")");
                if (dDb.isPresent() && pDb.isEmpty() && programmes.findByDepartmentAndName(dDb.get().getId(), pName).isPresent()) {
                    problems.add(dDb.get().getName() + " already has a programme called " + pName);
                }
            }
            if (!problems.isEmpty()) p.put(r.row(), problems);
        }
        return p;
    }

    private Map<String, Integer> saveStructure(List<StoredRow> rows) {
        int f = 0, d = 0, n = 0;
        Map<String, Faculty> fac = new HashMap<>();
        Map<String, Department> dep = new HashMap<>();
        for (StoredRow r : rows) {
            String fCode = Codes.unit(cell(r, 1)), dCode = Codes.unit(cell(r, 3));
            Faculty faculty = fac.get(fCode);
            if (faculty == null) {
                faculty = faculties.findByCode(fCode).orElse(null);
                if (faculty == null) {
                    faculty = new Faculty();
                    faculty.setId(UUID.randomUUID());
                    faculty.setName(cell(r, 0));
                    faculty.setCode(fCode);
                    faculty.setCreatedAt(clock.instant());
                    faculty.setUpdatedAt(clock.instant());
                    faculties.saveAndFlush(faculty);
                    f++;
                }
                fac.put(fCode, faculty);
            }
            Department department = dep.get(dCode);
            if (department == null) {
                department = departments.findByCode(dCode).orElse(null);
                if (department == null) {
                    department = new Department();
                    department.setId(UUID.randomUUID());
                    department.setFacultyId(faculty.getId());
                    department.setName(cell(r, 2));
                    department.setCode(dCode);
                    department.setCreatedAt(clock.instant());
                    department.setUpdatedAt(clock.instant());
                    departments.saveAndFlush(department);
                    d++;
                }
                dep.put(dCode, department);
            }
            Programme p = new Programme();
            p.setId(UUID.randomUUID());
            p.setDepartmentId(department.getId());
            p.setName(cell(r, 4));
            p.setCode(Codes.unit(cell(r, 5)));
            p.setDegree(cell(r, 6));
            p.setDurationYears(integer(cell(r, 7)));
            List<Integer> entry = levels(cell(r, 8));
            p.setEntryLevels(String.join(",", (entry == null || entry.isEmpty() ? List.of(100) : entry).stream().map(String::valueOf).toList()));
            p.setStatus("ACTIVE");
            p.setCreatedAt(clock.instant());
            p.setUpdatedAt(clock.instant());
            programmes.saveAndFlush(p);
            n++;
        }
        return new LinkedHashMap<>(Map.of("faculties", f, "departments", d, "programmes", n));
    }

    // ---- catalogue rows ----

    Map<Integer, List<String>> checkCatalogue(List<StoredRow> rows) {
        Map<Integer, List<String>> p = new TreeMap<>();
        Map<String, StoredRow> firstOfCode = new HashMap<>();
        Set<String> pairs = new HashSet<>();
        for (StoredRow r : rows) {
            List<String> problems = new ArrayList<>();
            String rawCode = cell(r, 0), code = Codes.course(rawCode);
            Integer units = integer(cell(r, 2)), level = level(cell(r, 3)), semester = semester(cell(r, 4));
            Map<String, String> f = CourseService.check(rawCode, cell(r, 1), units, level, semester);
            if (f.containsKey("code")) problems.add("Course code \"" + rawCode + "\" should look like CSC 201");
            if (f.containsKey("title")) problems.add("Give the course title");
            if (f.containsKey("units")) problems.add("Units \"" + cell(r, 2) + "\" should be a number from 1 to 12");
            if (f.containsKey("level")) problems.add("Level \"" + cell(r, 3) + "\" should be like 100 or 200L");
            if (f.containsKey("semester")) problems.add("Semester \"" + cell(r, 4) + "\" should be 1, 2, First or Second");
            String pCode = Codes.unit(cell(r, 5));
            Optional<Programme> prog = pCode.isEmpty() ? Optional.empty() : programmes.findByCode(pCode);
            if (pCode.isEmpty()) problems.add("Give the programme code the course is offered to");
            else if (prog.isEmpty()) problems.add("Programme code " + pCode + " not found: add the programme on Academic structure first");
            if (CourseService.kind(cell(r, 6)) == null) problems.add("Kind \"" + cell(r, 6) + "\" should be compulsory or elective");
            if (prog.isPresent() && level != null && level > prog.get().getDurationYears() * 100) {
                problems.add(prog.get().getName() + " only runs to " + prog.get().getDurationYears() * 100 + "L");
            }
            if (code != null) {
                courses.findByCode(code).ifPresent(c -> problems.add(c.getCode() + " is already in the catalogue (" + c.getTitle() + ")"));
                StoredRow first = firstOfCode.putIfAbsent(code, r);
                if (first != null && (!cell(first, 1).equalsIgnoreCase(cell(r, 1)) || !Objects.equals(integer(cell(first, 2)), units)
                        || !Objects.equals(level(cell(first, 3)), level) || !Objects.equals(semester(cell(first, 4)), semester))) {
                    problems.add(code + " has different details on row " + first.row() + ": a course has one title, units, level and semester");
                }
                if (!pCode.isEmpty() && !pairs.add(code + "|" + pCode)) problems.add(code + " is offered to " + pCode + " twice in the file");
            }
            if (!problems.isEmpty()) p.put(r.row(), problems);
        }
        return p;
    }

    private Map<String, Integer> saveCatalogue(List<StoredRow> rows) {
        Map<String, Course> made = new LinkedHashMap<>();
        int o = 0;
        for (StoredRow r : rows) {
            String code = Codes.course(cell(r, 0));
            Course c = made.get(code);
            if (c == null) {
                c = new Course();
                c.setId(UUID.randomUUID());
                c.setCode(code);
                c.setTitle(cell(r, 1));
                c.setUnits(integer(cell(r, 2)));
                c.setLevel(level(cell(r, 3)));
                c.setSemester(semester(cell(r, 4)));
                c.setCreatedAt(clock.instant());
                c.setUpdatedAt(clock.instant());
                courses.saveAndFlush(c);
                made.put(code, c);
            }
            CourseProgramme cp = new CourseProgramme();
            cp.setId(UUID.randomUUID());
            cp.setCourseId(c.getId());
            cp.setProgrammeId(programmes.findByCode(Codes.unit(cell(r, 5))).orElseThrow().getId());
            cp.setKind(CourseService.kind(cell(r, 6)));
            offers.save(cp);
            o++;
        }
        offers.flush();
        return new LinkedHashMap<>(Map.of("courses", made.size(), "offerings", o));
    }

    // ---- templates ----

    public static String template(String kind) {
        return switch (kind) {
            case "structure" -> Csv.line(STRUCTURE_COLUMNS)
                    + Csv.line(List.of("Faculty of Science", "SCI", "Department of Computer Science", "CSC", "B.Sc. Computer Science", "BSC-CSC", "B.Sc.", "4", "100;200"));
            case "catalogue" -> Csv.line(CATALOGUE_COLUMNS)
                    + Csv.line(List.of("CSC 201", "Data Structures", "3", "200", "1", "BSC-CSC", "compulsory"));
            default -> throw ApiException.notFound();
        };
    }

    // ---- helpers ----

    private ImportView view(ImportJob j) {
        List<Problem> problems = read(j.getProblems(), new TypeReference<>() {});
        Map<String, Integer> imported = j.getImported() == null ? null : read(j.getImported(), new TypeReference<>() {});
        String summary = j.getProblemRows() == 0 ? "All " + j.getTotalRows() + (j.getTotalRows() == 1 ? " row is ready" : " rows are ready")
                : j.getProblemRows() + (j.getProblemRows() == 1 ? " row needs fixing" : " rows need fixing");
        return new ImportView(j.getId(), j.getKind().name(), j.getFileName(), j.getStatus().name(), j.getTotalRows(), j.getReadyRows(),
                j.getProblemRows(), problems, summary, imported, j.getCreatedAt(), j.getConfirmedAt());
    }

    private static List<Problem> problemList(Map<Integer, List<String>> p) {
        return p.entrySet().stream().map(e -> new Problem(e.getKey(), String.join("; ", e.getValue()))).toList();
    }

    private static String cell(StoredRow r, int i) {
        return i < r.cells().size() && r.cells().get(i) != null ? r.cells().get(i).trim() : "";
    }

    private static Integer integer(String s) {
        try { return Integer.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    static Integer level(String s) {
        return integer(s.trim().toUpperCase(Locale.ROOT).replaceAll("\\s*L$", ""));
    }

    static Integer semester(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "1", "first", "1st", "first semester" -> 1;
            case "2", "second", "2nd", "second semester" -> 2;
            default -> null;
        };
    }

    /** "100;200" → [100, 200]; empty → []; anything else → null. */
    static List<Integer> levels(String s) {
        if (s.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : s.split("[;|/ ]+")) {
            Integer l = level(part);
            if (l == null) return null;
            out.add(l);
        }
        return out;
    }

    private static String join(List<Integer> rows) {
        return rows.size() == 2 ? rows.get(0) + " and " + rows.get(1) : String.join(", ", rows.stream().map(String::valueOf).toList());
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private <T> T read(String s, TypeReference<T> t) {
        try { return json.readValue(s, t); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
