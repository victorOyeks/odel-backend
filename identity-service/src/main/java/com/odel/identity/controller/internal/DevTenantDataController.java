package com.odel.identity.controller.internal;

import com.odel.common.security.SessionStore;
import com.odel.common.tenancy.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Development only: removes one university's accounts (and signs them out), when a test university is deleted. */
@RestController
@RequestMapping("/internal")
@ConditionalOnProperty(name = "odel.google.dev-stub", havingValue = "true")
public class DevTenantDataController {
    private final JdbcTemplate jdbc;
    private final SessionStore sessions;

    public DevTenantDataController(JdbcTemplate jdbc, SessionStore sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @DeleteMapping("/tenant-data")
    @Transactional
    public Map<String, Integer> delete() {
        UUID t = TenantContext.require();
        List<UUID> users = jdbc.queryForList("select id from users where tenant_id = ?", UUID.class, t);
        jdbc.update("delete from trusted_devices where tenant_id = ?", t);
        jdbc.update("delete from mfa_backup_codes where tenant_id = ?", t);
        jdbc.update("delete from role_assignments where tenant_id = ?", t);
        int n = jdbc.update("delete from users where tenant_id = ?", t);
        users.forEach(sessions::deleteAllForUser);
        return Map.of("users", n);
    }
}
