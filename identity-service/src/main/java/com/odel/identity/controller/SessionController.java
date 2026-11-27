package com.odel.identity.controller;

import com.odel.identity.entity.User;

import com.odel.identity.entity.RoleAssignment;

import com.odel.identity.service.SignInService;
import com.odel.identity.repository.UserRepository;
import com.odel.identity.repository.RoleAssignmentRepository;
import com.odel.identity.dto.SessionView;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.audit.AuditService;
import com.odel.common.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SessionController {
    private final SignInService signIn;
    private final SessionStore sessions;
    private final SessionCookies cookies;
    private final RoleAssignmentRepository roles;
    private final UserRepository users;
    private final AuditService audit;

    public SessionController(SignInService signIn, SessionStore sessions, SessionCookies cookies,
                             RoleAssignmentRepository roles, UserRepository users, AuditService audit) {
        this.signIn = signIn;
        this.sessions = sessions;
        this.cookies = cookies;
        this.roles = roles;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/session")
    @Transactional(readOnly = true)
    public SessionView session(CurrentUserArg.Holder cu) {
        return signIn.view(cu.user().sessionKey(), cu.user().data());
    }

    @PostMapping("/session/keepalive")
    public ResponseEntity<Void> keepalive(CurrentUserArg.Holder cu) {
        return ResponseEntity.noContent().build(); // the session filter already renewed the idle clock
    }

    @PostMapping("/auth/sign-out")
    @Transactional
    public ResponseEntity<Void> signOut(CurrentUserArg.Holder cu, HttpServletResponse response) {
        CurrentUser u = cu.user();
        users.findById(u.userId()).ifPresent(user -> audit.record(new AuditService.Entry(u.tenantId(), signIn.actor(user, u.data()),
                "SIGN_OUT", "user", user.getId(), user.getFullName() + " signed out", null, null)));
        sessions.delete(u.sessionKey(), u.userId());
        cookies.clearSession(response);
        return ResponseEntity.noContent().build();
    }

    public record ActiveRoleRequest(UUID roleAssignmentId) {}

    @PostMapping("/me/active-role")
    @Transactional
    public SessionView activeRole(CurrentUserArg.Holder cu, @RequestBody ActiveRoleRequest body) {
        CurrentUser u = cu.user();
        SessionData data = u.data();
        if (data.stage != SessionData.Stage.CHOOSE_ROLE && data.stage != SessionData.Stage.SIGNED_IN) {
            throw new ApiException(HttpStatus.CONFLICT, "wrong_stage", "Finish two-step sign-in first.");
        }
        RoleAssignment role = body.roleAssignmentId() == null ? null : roles.findById(body.roleAssignmentId())
                .filter(r -> r.getUserId().equals(u.userId())).orElse(null);
        if (role == null) throw ApiException.notFound();
        UUID previous = data.activeRoleId;
        data.activeRoleId = role.getId();
        data.activeRole = role.getRole();
        data.stage = SessionData.Stage.SIGNED_IN;
        User user = users.findById(u.userId()).orElseThrow();
        user.setLastRoleAssignmentId(role.getId());
        if (previous != null && !previous.equals(role.getId())) {
            audit.record(new AuditService.Entry(u.tenantId(), signIn.actor(user, data), "ROLE_SWITCHED", "role_assignment",
                    role.getId(), user.getFullName() + " switched to " + role.getRole().label(), null, null));
        }
        sessions.save(u.sessionKey(), data);
        return signIn.view(u.sessionKey(), data);
    }
}
