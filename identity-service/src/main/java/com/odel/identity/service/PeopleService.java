package com.odel.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.files.Csv;
import com.odel.common.files.StorageService;
import com.odel.common.files.StoredFiles;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.security.ScopeType;
import com.odel.common.security.SessionStore;
import com.odel.common.tenancy.TenantContext;
import com.odel.identity.RoleAssignment;
import com.odel.identity.User;
import com.odel.identity.client.AcademicClient;
import com.odel.identity.client.StaffMessagesClient;
import com.odel.identity.client.TenantLookClient;
import com.odel.identity.dto.PeopleDtos.*;
import com.odel.identity.dto.StaffDtos.*;
import com.odel.identity.repository.StaffRepository;
import com.odel.identity.repository.StaffRoleRepository;
import com.odel.identity.repository.TwoStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * People & roles (W-15 … W-15c, W-21): invite staff (one, or many from a CSV), change roles, suspend and reinstate,
 * resend invitations, and reset a staff member's two-step sign-in. Every change is audited.
 */
@Service
public class PeopleService {
    private static final Logger log = LoggerFactory.getLogger(PeopleService.class);
    static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    static final List<String> CSV_COLUMNS = List.of("full_name", "email", "role", "scope_type", "scope_code");
    private static final String IMPORT_KEY = "odel:people-import:";

    private final StaffRepository users;
    private final StaffRoleRepository roles;
    private final TwoStepRepository twoStep;
    private final SessionStore sessions;
    private final AcademicClient academic;
    private final TenantLookClient tenants;
    private final StaffMessagesClient messages;
    private final StorageService storage;
    private final StoredFiles files;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AuditService audit;
    private final Clock clock;

    public PeopleService(StaffRepository users, StaffRoleRepository roles, TwoStepRepository twoStep, SessionStore sessions, AcademicClient academic,
                         TenantLookClient tenants, StaffMessagesClient messages, StorageService storage, StoredFiles files, StringRedisTemplate redis,
                         ObjectMapper json, AuditService audit, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.twoStep = twoStep;
        this.sessions = sessions;
        this.academic = academic;
        this.tenants = tenants;
        this.messages = messages;
        this.storage = storage;
        this.files = files;
        this.redis = redis;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- reading ----

    @Transactional(readOnly = true)
    public PeoplePage list(String search, String role, String status, int page, int size) {
        int s = Math.min(Math.max(size, 1), 200), p = Math.max(page, 0);
        List<User> all = users.staff();
        Map<UUID, List<RoleAssignment>> byUser = rolesOf(all);
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<User> filtered = all.stream()
                .filter(u -> q.isEmpty() || u.getFullName().toLowerCase(Locale.ROOT).contains(q) || u.getEmail().toLowerCase(Locale.ROOT).contains(q))
                .filter(u -> role == null || role.isBlank() || byUser.getOrDefault(u.getId(), List.of()).stream().anyMatch(r -> r.getRole().name().equalsIgnoreCase(role)))
                .filter(u -> status == null || status.isBlank() || u.getStatus().name().equalsIgnoreCase(status))
                .toList();
        Counts counts = new Counts(all.size(), count(all, User.Status.ACTIVE), count(all, User.Status.INVITED), count(all, User.Status.SUSPENDED));
        return new PeoplePage(filtered.stream().skip((long) p * s).limit(s).map(u -> view(u, byUser.getOrDefault(u.getId(), List.of()))).toList(),
                p, s, filtered.size(), counts);
    }

    @Transactional(readOnly = true)
    public PersonDetail get(UUID id) {
        User u = staff(id);
        return new PersonDetail(view(u), academic.staffing(id));
    }

    PersonView view(User u) {
        return view(u, roles.forUsers(List.of(u.getId())));
    }

    static PersonView view(User u, List<RoleAssignment> rs) {
        List<RoleView> rv = rs.stream().map(r -> new RoleView(r.getId(), r.getRole().name(), r.getRole().label(), r.getScopeType().name(),
                r.getScopeId(), r.getScopeLabel())).toList();
        String scope = rs.stream().map(RoleAssignment::getScopeLabel).filter(Objects::nonNull).distinct().collect(Collectors.joining(" · "));
        return new PersonView(u.getId(), u.getFullName(), u.getEmail(), u.initials(), u.getStatus().name(), rv, scope.isEmpty() ? null : scope,
                u.mfaEnabled(), u.getInvitedAt(), u.getAcceptedAt(), u.getLastSignInAt(), u.getSuspendedAt(), u.getSuspendedReason());
    }

    // ---- for other services ----

    @Transactional(readOnly = true)
    public List<StaffMember> staffList(String role) {
        List<User> all = users.staff();
        Map<UUID, List<RoleAssignment>> byUser = rolesOf(all);
        return all.stream()
                .filter(u -> role == null || byUser.getOrDefault(u.getId(), List.of()).stream().anyMatch(r -> r.getRole().name().equalsIgnoreCase(role)))
                .map(u -> new StaffMember(u.getId(), u.getFullName(), u.getEmail(), u.getStatus().name(), byUser.getOrDefault(u.getId(), List.of()).stream()
                        .map(r -> new StaffRole(r.getRole().name(), r.getScopeType().name(), r.getScopeId(), r.getScopeLabel())).toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffSummary summary() {
        List<User> all = users.staff();
        Map<UUID, List<RoleAssignment>> byUser = rolesOf(all);
        Map<String, Long> byRole = new TreeMap<>();
        byUser.values().forEach(list -> list.stream().map(r -> r.getRole().name()).distinct().forEach(r -> byRole.merge(r, 1L, Long::sum)));
        return new StaffSummary(all.size(), count(all, User.Status.INVITED), count(all, User.Status.ACTIVE), count(all, User.Status.SUSPENDED), byRole);
    }

    // ---- invite ----

    private record Resolved(Role role, ScopeType scopeType, UUID scopeId, String label) {}

    /** Checks and labels the roles; field errors keyed like roles[0].scopeId. */
    private List<Resolved> resolveRoles(List<RoleIn> in, Map<String, String> f, String universityName) {
        List<Resolved> out = new ArrayList<>();
        if (in == null || in.isEmpty()) { f.put("roles", "Choose at least one role."); return out; }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            RoleIn r = in.get(i);
            String k = "roles[" + i + "]";
            Optional<Role> role = r == null ? Optional.empty() : ScopeRules.role(r.role());
            if (role.isEmpty()) { f.put(k + ".role", "Choose one of the staff roles."); continue; }
            List<ScopeType> allowed = ScopeRules.allowed(role.get());
            ScopeType scope = ScopeRules.scope(r.scopeType()).orElse(allowed.size() == 1 ? allowed.get(0) : null);
            if (scope == null || !allowed.contains(scope)) {
                f.put(k + ".scopeType", role.get().label() + " can be given for " + allowed.stream().map(ScopeRules.SCOPE_NAME::get).collect(Collectors.joining(", ", "", ".")));
                continue;
            }
            String label;
            UUID scopeId = scope == ScopeType.UNIVERSITY ? null : r.scopeId();
            if (scope == ScopeType.UNIVERSITY) {
                label = universityName;
            } else if (scopeId == null) {
                f.put(k + ".scopeId", "Choose " + ScopeRules.SCOPE_NAME.get(scope) + ".");
                continue;
            } else {
                Optional<Scope> sc = academic.scope(scope.name(), scopeId);
                if (sc.isEmpty()) { f.put(k + ".scopeId", "Choose " + ScopeRules.SCOPE_NAME.get(scope) + " from Academic setup."); continue; }
                label = sc.get().label();
            }
            if (seen.add(role.get() + "|" + scope + "|" + scopeId)) out.add(new Resolved(role.get(), scope, scopeId, label));
        }
        return out;
    }

    @Transactional
    public PersonView invite(InviteRequest in, CurrentUser cu) {
        AuditService.Actor actor = actor(cu);
        TenantLook t = tenants.current();
        Map<String, String> f = new LinkedHashMap<>();
        String email = in.email() == null ? "" : in.email().trim().toLowerCase(Locale.ROOT);
        if (in.fullName() == null || in.fullName().isBlank()) f.put("fullName", "Type their full name.");
        else if (in.fullName().trim().length() > 200) f.put("fullName", "Keep the name under 200 characters.");
        if (!email.matches(EMAIL)) f.put("email", "Enter their university email, for example name@" + t.googleDomain() + ".");
        else if (t.googleDomain() != null && !email.endsWith("@" + t.googleDomain())) {
            f.put("email", "Use an @" + t.googleDomain() + " address: staff sign in with the university's Google accounts.");
        }
        List<Resolved> rs = resolveRoles(in.roles(), f, t.name());
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Some details need fixing.", f);
        if (users.findAll().stream().anyMatch(u -> u.getEmail().equalsIgnoreCase(email))) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", email + " already has an account here.");
        }
        User u = create(in.fullName().trim(), email, rs, actor);
        return view(u);
    }

    private User create(String fullName, String email, List<Resolved> rs, AuditService.Actor actor) {
        Instant now = clock.instant();
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setFullName(fullName);
        u.setAuthType(User.AuthType.GOOGLE);
        u.setStatus(User.Status.INVITED);
        u.setInvitedAt(now);
        u.setInvitedBy(actor.userId());
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        users.saveAndFlush(u);
        for (Resolved r : rs) roles.save(assignment(u, r, actor));
        roles.flush();
        String what = rs.stream().map(r -> ScopeRules.invitationLabel(r.role(), r.scopeType(), r.label())).collect(Collectors.joining(" and "));
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "STAFF_INVITED", "user", u.getId(),
                "Invited " + fullName + " as " + what, null, Map.of("email", email, "roles", rs.stream().map(r -> r.role().name()).toList())));
        messages.invitation(u, what);
        return u;
    }

    private RoleAssignment assignment(User u, Resolved r, AuditService.Actor actor) {
        RoleAssignment a = new RoleAssignment();
        a.setId(UUID.randomUUID());
        a.setUserId(u.getId());
        a.setRole(r.role());
        a.setScopeType(r.scopeType());
        a.setScopeId(r.scopeId());
        a.setScopeLabel(r.label());
        a.setCreatedAt(clock.instant());
        a.setCreatedBy(actor.userId());
        return a;
    }

    // ---- bulk invite (W-15c) ----

    public UploadResponse upload(UploadRequest in) {
        String name = in.fileName() == null ? "" : in.fileName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv")) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Upload a CSV file.", Map.of("file", "Save the sheet as CSV and upload that."));
        if (in.size() == null || in.size() <= 0 || in.size() > 2L * 1024 * 1024) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The file must be under 2 MB.", Map.of("file", "Use a file under 2 MB."));
        }
        String key = "imports/" + TenantContext.require() + "/" + UUID.randomUUID() + ".csv";
        Duration ttl = Duration.ofMinutes(15);
        return new UploadResponse(key, storage.presignPut(key, ttl), "PUT", Map.of("Content-Type", "text/csv"), clock.instant().plus(ttl));
    }

    public static String template() {
        return Csv.line(CSV_COLUMNS) + Csv.line(List.of("Dr Funmi Bello", "f.bello@wesleyuni.edu.ng", "Lecturer", "COURSE", "CSC 201"))
                + Csv.line(List.of("Mrs Bisi Ade", "b.ade@wesleyuni.edu.ng", "Centre coordinator", "CENTRE", "AKR"));
    }

    @Transactional(readOnly = true)
    public ImportCheck check(CheckRequest in) {
        if (in.key() == null || !in.key().startsWith("imports/" + TenantContext.require() + "/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "upload_missing", "We could not find the uploaded file. Upload it again.");
        }
        List<List<String>> parsed = Csv.parse(files.read(in.key(), 2L * 1024 * 1024));
        if (!Csv.headerMatches(parsed, CSV_COLUMNS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_header", "The columns do not match the template: download the template and copy your rows into it. Expected: "
                    + String.join(", ", CSV_COLUMNS) + ".");
        }
        List<Csv.Row> rows = Csv.dataRows(parsed);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "empty_file", "The file has no rows under the header.");
        TenantLook t = tenants.current();
        Set<String> existing = users.findAll().stream().map(u -> u.getEmail().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, Integer> inFile = new HashMap<>();
        List<PendingInvite> ready = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        for (Csv.Row r : rows) {
            List<String> p = new ArrayList<>();
            String name = r.get(0), email = r.get(1).toLowerCase(Locale.ROOT);
            if (name.isEmpty()) p.add("Give the person's full name");
            if (!email.matches(EMAIL)) p.add("\"" + r.get(1) + "\" is not an email address");
            else if (t.googleDomain() != null && !email.endsWith("@" + t.googleDomain())) p.add(email + " is not an @" + t.googleDomain() + " address");
            else if (existing.contains(email)) p.add(email + " already has an account here");
            else if (inFile.containsKey(email)) p.add(email + " is also on row " + inFile.get(email));
            else inFile.put(email, r.number());
            Optional<Role> role = ScopeRules.role(r.get(2));
            RoleIn roleIn = null;
            if (role.isEmpty()) p.add("Role \"" + r.get(2) + "\" is not one of the staff roles");
            else {
                List<ScopeType> allowed = ScopeRules.allowed(role.get());
                ScopeType scope = ScopeRules.scope(r.get(3)).orElse(allowed.size() == 1 ? allowed.get(0) : (r.get(3).isEmpty() ? ScopeType.UNIVERSITY : null));
                if (scope == null || !allowed.contains(scope)) p.add(role.get().label() + " cannot have the scope \"" + r.get(3) + "\"");
                else if (scope == ScopeType.UNIVERSITY) roleIn = new RoleIn(role.get().name(), scope.name(), null);
                else if (r.get(4).isEmpty()) p.add("Give the " + scope.name().toLowerCase(Locale.ROOT) + " code");
                else {
                    Optional<Scope> sc = academic.resolve(scope.name(), r.get(4));
                    if (sc.isEmpty()) p.add(scope.name().charAt(0) + scope.name().substring(1).toLowerCase(Locale.ROOT) + " \"" + r.get(4) + "\" not found in Academic setup");
                    else roleIn = new RoleIn(role.get().name(), scope.name(), sc.get().id());
                }
            }
            if (p.isEmpty()) ready.add(new PendingInvite(r.number(), name, email, List.of(roleIn)));
            else problems.add(new Problem(r.number(), String.join("; ", p)));
        }
        PendingImport pending = new PendingImport(UUID.randomUUID(), in.fileName() == null ? "staff.csv" : in.fileName(), rows.size(), ready, problems);
        try {
            redis.opsForValue().set(IMPORT_KEY + TenantContext.require() + ":" + pending.id(), json.writeValueAsString(pending), Duration.ofHours(1));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return checkView(pending, "CHECKED", null);
    }

    @Transactional
    public ImportCheck confirm(UUID id, CurrentUser cu) {
        String key = IMPORT_KEY + TenantContext.require() + ":" + id;
        String raw = redis.opsForValue().getAndDelete(key);
        if (raw == null) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "This check has expired or was already used. Upload the file again.");
        PendingImport pending;
        try { pending = json.readValue(raw, PendingImport.class); } catch (Exception e) { throw new IllegalStateException(e); }
        AuditService.Actor actor = actor(cu);
        TenantLook t = tenants.current();
        int invited = 0;
        for (PendingInvite p : pending.ready()) {
            if (users.findAll().stream().anyMatch(u -> u.getEmail().equalsIgnoreCase(p.email()))) continue;
            Map<String, String> f = new HashMap<>();
            List<Resolved> rs = resolveRoles(p.roles(), f, t.name());
            if (!f.isEmpty() || rs.isEmpty()) continue;
            create(p.fullName(), p.email(), rs, actor);
            invited++;
        }
        return checkView(pending, "IMPORTED", invited);
    }

    private static ImportCheck checkView(PendingImport p, String status, Integer invited) {
        String summary = p.problems().isEmpty() ? "All " + p.totalRows() + (p.totalRows() == 1 ? " row is ready" : " rows are ready")
                : p.problems().size() + (p.problems().size() == 1 ? " row needs fixing" : " rows need fixing");
        return new ImportCheck(p.id(), p.fileName(), status, p.totalRows(), p.ready().size(), p.problems().size(), p.problems(), summary, invited);
    }

    // ---- roles ----

    @Transactional
    public PersonView setRoles(UUID id, RolesRequest in, CurrentUser cu) {
        User u = staff(id);
        AuditService.Actor actor = actor(cu);
        Map<String, String> f = new LinkedHashMap<>();
        List<Resolved> wanted = resolveRoles(in.roles(), f, tenants.current().name());
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Some details need fixing.", f);
        List<RoleAssignment> current = roles.forUsers(List.of(id));
        Set<String> wantedKeys = wanted.stream().map(r -> r.role() + "|" + r.scopeType() + "|" + r.scopeId()).collect(Collectors.toSet());
        List<RoleAssignment> removed = current.stream().filter(r -> !wantedKeys.contains(r.getRole() + "|" + r.getScopeType() + "|" + r.getScopeId())).toList();
        Set<String> currentKeys = current.stream().map(r -> r.getRole() + "|" + r.getScopeType() + "|" + r.getScopeId()).collect(Collectors.toSet());
        List<Resolved> added = wanted.stream().filter(r -> !currentKeys.contains(r.role() + "|" + r.scopeType() + "|" + r.scopeId())).toList();
        if (u.getId().equals(cu.userId()) && removed.stream().anyMatch(r -> r.getRole() == Role.ODEL_ADMIN)
                && wanted.stream().noneMatch(r -> r.role() == Role.ODEL_ADMIN)) {
            throw new ApiException(HttpStatus.CONFLICT, "own_admin_role", "You cannot remove your own ODeL admin role. Ask another ODeL admin.");
        }
        for (RoleAssignment r : removed) {
            audit.record(new AuditService.Entry(TenantContext.require(), actor, "ROLE_REMOVED", "role_assignment", r.getId(),
                    "Removed " + u.getFullName() + "'s role " + ScopeRules.invitationLabel(r.getRole(), r.getScopeType(), r.getScopeLabel()), null, null));
        }
        if (!removed.isEmpty()) {
            roles.deleteIds(removed.stream().map(RoleAssignment::getId).toList());
            if (removed.stream().anyMatch(r -> r.getId().equals(u.getLastRoleAssignmentId()))) u.setLastRoleAssignmentId(null);
        }
        for (Resolved r : added) {
            RoleAssignment a = roles.save(assignment(u, r, actor));
            audit.record(new AuditService.Entry(TenantContext.require(), actor, "ROLE_GRANTED", "role_assignment", a.getId(),
                    "Gave " + u.getFullName() + " the role " + ScopeRules.invitationLabel(r.role(), r.scopeType(), r.label()), null, null));
        }
        u.setUpdatedAt(clock.instant());
        users.saveAndFlush(u);
        roles.flush();
        // A removed role must stop working at once: the person signs in again and sees their new roles.
        if (!removed.isEmpty()) sessions.deleteAllForUser(u.getId());
        return view(u);
    }

    // ---- suspend, reinstate, resend, two-step reset ----

    public SuspendCheck suspendCheck(UUID id) {
        User u = staff(id);
        List<Place> places = academic.staffing(id).stream().filter(Place::current).toList();
        List<String> warnings = new ArrayList<>();
        String pronoun = "They";
        long groups = places.stream().filter(p -> p.kind().equals("FACILITATOR")).count();
        long lectures = places.stream().filter(p -> p.kind().equals("LECTURER")).count();
        long centres = places.stream().filter(p -> p.kind().equals("COORDINATOR")).count();
        if (lectures > 0) warnings.add(pronoun + " teach " + lectures + (lectures == 1 ? " course" : " courses") + list(places, "LECTURER")
                + ". Assign another lecturer so students are not left without one.");
        if (groups > 0) warnings.add(pronoun + " facilitate " + groups + (groups == 1 ? " course group" : " course groups") + list(places, "FACILITATOR")
                + ". Reassign them so students are not left without support.");
        if (centres > 0) warnings.add(pronoun + " coordinate " + centres + (centres == 1 ? " centre" : " centres") + list(places, "COORDINATOR") + ". Assign another coordinator.");
        if (u.getStatus() == User.Status.SUSPENDED) warnings.add(u.getFullName() + " is already suspended.");
        return new SuspendCheck(warnings, places);
    }

    private static String list(List<Place> places, String kind) {
        return places.stream().filter(p -> p.kind().equals(kind)).map(p -> p.what().replaceFirst("^[^·]+· ", ""))
                .collect(Collectors.joining(", ", " (", ")"));
    }

    @Transactional
    public PersonView suspend(UUID id, ReasonRequest in, CurrentUser cu) {
        User u = staff(id);
        if (u.getId().equals(cu.userId())) throw new ApiException(HttpStatus.CONFLICT, "self", "You cannot suspend yourself.");
        if (u.getStatus() == User.Status.SUSPENDED) throw new ApiException(HttpStatus.CONFLICT, "already_suspended", u.getFullName() + " is already suspended.");
        String reason = in == null || in.reason() == null ? "" : in.reason().trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Give a reason (it is kept in the audit log).", Map.of("reason", "Write why, in up to 500 characters."));
        }
        String before = u.getStatus().name();
        u.setStatus(User.Status.SUSPENDED);
        u.setSuspendedAt(clock.instant());
        u.setSuspendedReason(reason);
        u.setUpdatedAt(clock.instant());
        users.saveAndFlush(u);
        sessions.deleteAllForUser(u.getId());
        audit.record(new AuditService.Entry(TenantContext.require(), actor(cu), "STAFF_SUSPENDED", "user", u.getId(),
                "Suspended " + u.getFullName() + ": " + reason, Map.of("status", before), Map.of("status", "SUSPENDED", "reason", reason)));
        return view(u);
    }

    @Transactional
    public PersonView reinstate(UUID id, CurrentUser cu) {
        User u = staff(id);
        if (u.getStatus() != User.Status.SUSPENDED) throw new ApiException(HttpStatus.CONFLICT, "not_suspended", u.getFullName() + " is not suspended.");
        u.setStatus(u.getAcceptedAt() != null ? User.Status.ACTIVE : User.Status.INVITED);
        u.setSuspendedAt(null);
        u.setSuspendedReason(null);
        u.setUpdatedAt(clock.instant());
        users.saveAndFlush(u);
        audit.record(new AuditService.Entry(TenantContext.require(), actor(cu), "STAFF_REINSTATED", "user", u.getId(),
                "Restored " + u.getFullName() + "'s access", Map.of("status", "SUSPENDED"), Map.of("status", u.getStatus().name())));
        return view(u);
    }

    @Transactional
    public void resendInvitation(UUID id, CurrentUser cu) {
        User u = staff(id);
        if (u.getStatus() != User.Status.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, u.getStatus() == User.Status.SUSPENDED ? "suspended" : "already_active",
                    u.getStatus() == User.Status.SUSPENDED ? u.getFullName() + " is suspended. Restore their access first." : u.getFullName() + " has already signed in.");
        }
        List<RoleAssignment> rs = roles.forUsers(List.of(id));
        messages.invitation(u, rs.stream().map(r -> ScopeRules.invitationLabel(r.getRole(), r.getScopeType(), r.getScopeLabel())).collect(Collectors.joining(" and ")));
        audit.record(new AuditService.Entry(TenantContext.require(), actor(cu), "INVITATION_RESENT", "user", u.getId(), "Resent the invitation to " + u.getFullName(), null, null));
    }

    @Transactional
    public void resetTwoStep(UUID id, ReasonRequest in, CurrentUser cu) {
        User u = staff(id);
        if (u.getId().equals(cu.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "self", "You cannot reset your own two-step sign-in. Use a backup code, or ask another ODeL admin.");
        }
        u.setMfaSecretEnc(null);
        u.setMfaEnabledAt(null);
        u.setUpdatedAt(clock.instant());
        users.saveAndFlush(u);
        int devices = twoStep.deleteTrustedDevices(u.getId());
        twoStep.deleteBackupCodes(u.getId());
        sessions.deleteAllForUser(u.getId());
        AuditService.Actor actor = actor(cu);
        String reason = in == null || in.reason() == null || in.reason().isBlank() ? null : in.reason().trim();
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "MFA_RESET", "user", u.getId(),
                "Reset " + u.getFullName() + "'s two-step sign-in" + (reason == null ? "" : ": " + reason), null, Map.of("trustedBrowsersRemoved", devices)));
        try {
            messages.twoStepReset(u, actor.name());
        } catch (RuntimeException e) {
            log.warn("Could not send the two-step reset email to {}: {}", u.getId(), e.getMessage());
        }
    }

    // ---- helpers ----

    private User staff(UUID id) {
        return users.findById(id).filter(u -> u.getAuthType() == User.AuthType.GOOGLE).orElseThrow(ApiException::notFound);
    }

    public AuditService.Actor actor(CurrentUser cu) {
        String name = users.findById(cu.userId()).map(User::getFullName).orElse("Staff member");
        return new AuditService.Actor(cu.userId(), cu.tenantId(), name, cu.activeRole() == null ? null : cu.activeRole().name());
    }

    private Map<UUID, List<RoleAssignment>> rolesOf(List<User> list) {
        if (list.isEmpty()) return Map.of();
        return roles.forUsers(list.stream().map(User::getId).toList()).stream().collect(Collectors.groupingBy(RoleAssignment::getUserId));
    }

    private static long count(List<User> list, User.Status s) {
        return list.stream().filter(u -> u.getStatus() == s).count();
    }
}
