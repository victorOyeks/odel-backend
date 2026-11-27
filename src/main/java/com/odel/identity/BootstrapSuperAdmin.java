package com.odel.identity;

import com.odel.common.OdelProperties;
import com.odel.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** On an empty platform, creates the first platform admin from ODEL_BOOTSTRAP_SUPER_ADMIN_EMAIL. */
@Component
public class BootstrapSuperAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapSuperAdmin.class);
    private final OdelProperties props;
    private final UserRepository users;
    private final RoleAssignmentRepository roles;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BootstrapSuperAdmin(OdelProperties props, UserRepository users, RoleAssignmentRepository roles,
                               TransactionTemplate tx, Clock clock) {
        this.props = props;
        this.users = users;
        this.roles = roles;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = props.bootstrap().superAdminEmail();
        if (email == null || email.isBlank()) return;
        TenantContext.runAs(TenantContext.PLATFORM_TENANT_ID, () -> tx.executeWithoutResult(s -> {
            if (roles.existsByRole(Role.SUPER_ADMIN)) return;
            Instant now = clock.instant();
            User u = users.findByEmail(email).orElseGet(() -> {
                User n = new User();
                n.setId(UUID.randomUUID());
                n.setEmail(email.trim().toLowerCase());
                n.setFullName(props.bootstrap().superAdminName());
                n.setAuthType(User.AuthType.GOOGLE);
                n.setStatus(User.Status.INVITED);
                n.setInvitedAt(now);
                n.setCreatedAt(now);
                n.setUpdatedAt(now);
                return users.save(n);
            });
            RoleAssignment r = new RoleAssignment();
            r.setId(UUID.randomUUID());
            r.setUserId(u.getId());
            r.setRole(Role.SUPER_ADMIN);
            r.setScopeType(ScopeType.PLATFORM);
            r.setScopeLabel("ODeL Platform");
            r.setCreatedAt(now);
            roles.save(r);
            log.info("Created the first platform admin {}", u.getEmail());
        }));
    }
}
