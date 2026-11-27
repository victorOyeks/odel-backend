package com.odel.identity.controller.internal;

import com.odel.identity.dto.IdentityInternalDtos.*;

import com.odel.identity.entity.User;
import com.odel.identity.entity.RoleAssignment;
import com.odel.identity.repository.UserRepository;
import com.odel.identity.repository.RoleAssignmentRepository;

import com.odel.common.ApiException;
import com.odel.common.security.Role;
import com.odel.common.security.ScopeType;
import com.odel.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/** Called by other services only (X-Internal-Key). The university comes in the X-Odel-Tenant-Id header. */
@RestController
@RequestMapping("/internal")
public class IdentityInternalController {
    private final UserRepository users;
    private final RoleAssignmentRepository roles;
    private final Clock clock;

    public IdentityInternalController(UserRepository users, RoleAssignmentRepository roles, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.clock = clock;
    }

    /** Invites a staff member (a university's first admin, from tenant-service). */
    @PostMapping("/users")
    @Transactional
    public StaffView invite(@RequestBody NewStaff in) {
        TenantContext.require();
        if (users.findByEmail(in.email()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", in.email() + " already has an account here.");
        }
        Instant now = clock.instant();
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(in.email().trim().toLowerCase());
        u.setFullName(in.fullName().trim());
        u.setAuthType(User.AuthType.GOOGLE);
        u.setStatus(User.Status.INVITED);
        u.setInvitedAt(now);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        users.save(u);
        RoleAssignment r = new RoleAssignment();
        r.setId(UUID.randomUUID());
        r.setUserId(u.getId());
        r.setRole(in.role());
        r.setScopeType(in.scopeType());
        r.setScopeLabel(in.scopeLabel());
        r.setCreatedAt(now);
        r.setCreatedBy(in.createdBy());
        roles.save(r);
        return StaffView.of(u);
    }

    /** The university's first ODeL admin: the earliest ODEL_ADMIN role. */
    @GetMapping("/first-admin")
    @Transactional(readOnly = true)
    public StaffView firstAdmin() {
        return roles.findAll().stream().filter(r -> r.getRole() == Role.ODEL_ADMIN)
                .min(Comparator.comparing(RoleAssignment::getCreatedAt))
                .flatMap(r -> users.findInTenant(r.getUserId())).map(StaffView::of)
                .orElseThrow(ApiException::notFound);
    }

    @GetMapping("/users/{id}")
    @Transactional(readOnly = true)
    public StaffView user(@PathVariable UUID id) {
        return users.findInTenant(id).map(StaffView::of).orElseThrow(ApiException::notFound);
    }
}
