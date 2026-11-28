package com.odel.identity.controller.internal;

import com.odel.common.ApiException;
import com.odel.common.security.Role;
import com.odel.common.security.ScopeType;
import com.odel.common.security.SessionStore;
import com.odel.common.tenancy.TenantContext;
import com.odel.identity.entity.User;
import com.odel.identity.repository.UserRepository;
import com.odel.identity.repository.StudentUserRepository;
import com.odel.identity.dto.StudentAccountDtos.*;
import com.odel.identity.service.StudentAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** student-service manages student accounts through here (X-Internal-Key; the university in X-Odel-Tenant-Id). */
@RestController
@RequestMapping("/internal/students")
public class StudentAccountsInternalController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final StudentUserRepository byId;
    private final SessionStore sessions;
    private final StudentAuthService auth;
    private final Clock clock;
    private final com.odel.identity.service.StudentAccountService accounts;

    public StudentAccountsInternalController(JdbcTemplate jdbc, UserRepository users, StudentUserRepository byId, SessionStore sessions, StudentAuthService auth, Clock clock,
                                             com.odel.identity.service.StudentAccountService accounts) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.users = users;
        this.byId = byId;
        this.sessions = sessions;
        this.auth = auth;
        this.clock = clock;
    }

    /**
     * Creates (or updates, by matric number) PASSWORD accounts with status INVITED and role STUDENT, in batches:
     * a whole intake of 3,500 in one call.
     */
    @PostMapping("/accounts")
    @Transactional
    public CreatedResponse upsert(@RequestBody AccountsRequest body) {
        UUID tenant = TenantContext.require();
        List<Account> in = body.accounts() == null ? List.of() : body.accounts();
        Map<String, UUID> existing = new HashMap<>();
        for (int i = 0; i < in.size(); i += 1000) {
            String[] chunk = in.subList(i, Math.min(in.size(), i + 1000)).stream().map(a -> StudentAuthService.normalizeMatric(a.matricNumber())).toArray(String[]::new);
            jdbc.query("select id, matric_number from users where tenant_id = ? and matric_number = any (?)",
                    rs -> { existing.put(rs.getString(2), rs.getObject(1, UUID.class)); }, tenant, chunk);
        }
        Timestamp now = Timestamp.from(clock.instant());
        List<Object[]> inserts = new ArrayList<>(), roleRows = new ArrayList<>(), updates = new ArrayList<>();
        List<Created> out = new ArrayList<>();
        for (Account a : in) {
            String matric = StudentAuthService.normalizeMatric(a.matricNumber());
            String email = blank(a.email()) ? null : a.email().trim().toLowerCase(Locale.ROOT);
            UUID id = existing.get(matric);
            if (id == null) {
                id = UUID.randomUUID();
                existing.put(matric, id);
                inserts.add(new Object[]{id, tenant, email, a.fullName().trim(), a.phone(), matric, a.studentId(), now, now, now});
                roleRows.add(new Object[]{UUID.randomUUID(), tenant, id, now});
                out.add(new Created(a.studentId(), id, true));
            } else {
                updates.add(new Object[]{email, a.fullName().trim(), a.phone(), a.studentId(), now, id, tenant});
                out.add(new Created(a.studentId(), id, false));
            }
        }
        try {
            jdbc.batchUpdate("""
                    insert into users (id, tenant_id, email, full_name, phone, auth_type, status, matric_number, student_id, invited_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, 'PASSWORD', 'INVITED', ?, ?, ?, ?, ?)""", inserts);
            jdbc.batchUpdate("""
                    insert into role_assignments (id, tenant_id, user_id, role, scope_type, created_at) values (?, ?, ?, 'STUDENT', 'UNIVERSITY', ?)""", roleRows);
            jdbc.batchUpdate("update users set email = ?, full_name = ?, phone = ?, student_id = ?, updated_at = ? where id = ? and tenant_id = ?", updates);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", "An email in this file is already used by another account. Check the file again.");
        }
        return new CreatedResponse(out);
    }

    /** Emails already used by another account (staff, or a student with a different matric number). */
    @PostMapping("/email-conflicts")
    @Transactional(readOnly = true)
    public ConflictsResponse emailConflicts(@RequestBody EmailCheckRequest body) {
        UUID tenant = TenantContext.require();
        List<EmailEntry> in = body.entries() == null ? List.of() : body.entries();
        Map<String, String> owner = new HashMap<>(); // email -> matric (or "" for staff)
        for (int i = 0; i < in.size(); i += 1000) {
            String[] chunk = in.subList(i, Math.min(in.size(), i + 1000)).stream().map(e -> e.email().trim().toLowerCase(Locale.ROOT)).toArray(String[]::new);
            jdbc.query("select lower(email), coalesce(matric_number, '') from users where tenant_id = ? and lower(email) = any (?)",
                    rs -> { owner.put(rs.getString(1), rs.getString(2)); }, tenant, chunk);
        }
        List<EmailEntry> conflicts = new ArrayList<>();
        for (EmailEntry e : in) {
            String o = owner.get(e.email().trim().toLowerCase(Locale.ROOT));
            if (o != null && !o.equals(StudentAuthService.normalizeMatric(e.matricNumber()))) conflicts.add(e);
        }
        return new ConflictsResponse(conflicts);
    }

    @GetMapping("/accounts/{userId}")
    @Transactional(readOnly = true)
    public AccountView account(@PathVariable UUID userId) {
        User u = student(userId);
        Instant locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(clock.instant()) ? u.getLockedUntil() : null;
        return new AccountView(u.getId(), u.getStatus().name(), u.getPasswordHash() == null ? null : u.getAcceptedAt(), u.getLastSignInAt(),
                locked, u.getConsentVersion(), u.getConsentAt());
    }

    @PostMapping("/accounts/{userId}/change")
    @Transactional
    public AccountView change(@PathVariable UUID userId, @RequestBody ChangeRequest body) {
        User u = student(userId);
        if (body.matricNumber() != null) {
            String m = StudentAuthService.normalizeMatric(body.matricNumber());
            users.findByMatricNumber(m).filter(o -> !o.getId().equals(userId)).ifPresent(o -> {
                throw new ApiException(HttpStatus.CONFLICT, "matric_taken", m + " is already used by another student.");
            });
            u.setMatricNumber(m);
        }
        if (body.email() != null) {
            String e = body.email().isBlank() ? null : body.email().trim().toLowerCase(Locale.ROOT);
            if (e != null) users.findByEmail(e).filter(o -> !o.getId().equals(userId)).ifPresent(o -> {
                throw new ApiException(HttpStatus.CONFLICT, "email_taken", e + " is already used by another account.");
            });
            u.setEmail(e);
        }
        if (body.fullName() != null && !body.fullName().isBlank()) u.setFullName(body.fullName().trim());
        if (body.phone() != null) u.setPhone(body.phone().isBlank() ? null : body.phone().trim());
        u.setUpdatedAt(clock.instant());
        users.save(u);
        return account(userId);
    }

    /** Suspends the account and signs the student out of every browser at once. */
    @PostMapping("/accounts/{userId}/suspend")
    @Transactional
    public AccountView suspend(@PathVariable UUID userId) {
        User u = student(userId);
        u.setStatus(User.Status.SUSPENDED);
        u.setUpdatedAt(clock.instant());
        users.save(u);
        sessions.deleteAllForUser(userId);
        return account(userId);
    }

    @PostMapping("/accounts/{userId}/reinstate")
    @Transactional
    public AccountView reinstate(@PathVariable UUID userId) {
        User u = student(userId);
        u.setStatus(u.getPasswordHash() != null ? User.Status.ACTIVE : User.Status.INVITED);
        u.setFailedSignIns(0);
        u.setLockedUntil(null);
        u.setUpdatedAt(clock.instant());
        users.save(u);
        return account(userId);
    }

    @PostMapping("/accounts/{userId}/password-reset")
    public ResponseEntity<Void> passwordReset(@PathVariable UUID userId) {
        auth.officeReset(userId);
        return ResponseEntity.noContent().build();
    }

    /** B3: student-service asks before saving a new phone number (L-14b "Your password · to confirm it is you"). */
    @PostMapping("/accounts/{userId}/check-password")
    public ResponseEntity<Void> checkPassword(@PathVariable UUID userId, @RequestBody java.util.Map<String, String> body) {
        accounts.checkPassword(userId, body == null ? null : body.get("password"), "password");
        return ResponseEntity.noContent().build();
    }

    private User student(UUID userId) {
        return byId.findInTenant(userId).filter(User::isStudent).orElseThrow(ApiException::notFound);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
