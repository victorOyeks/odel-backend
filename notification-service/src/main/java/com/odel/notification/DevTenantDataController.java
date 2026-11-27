package com.odel.notification;

import com.odel.common.tenancy.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Development only: removes one university's notifications, emails, push sign-ups and jobs, when a test university is deleted. */
@RestController
@RequestMapping("/internal")
@ConditionalOnProperty(name = "odel.google.dev-stub", havingValue = "true")
public class DevTenantDataController {
    private final JdbcTemplate jdbc;

    public DevTenantDataController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @DeleteMapping("/tenant-data")
    @Transactional
    public Map<String, Integer> delete() {
        UUID t = TenantContext.require();
        jdbc.update("delete from jobs where tenant_id = ?", t);
        int m = jdbc.update("delete from outbound_messages where tenant_id = ?", t);
        jdbc.update("delete from notifications where tenant_id = ?", t);
        jdbc.update("delete from push_tokens where tenant_id = ?", t);
        return Map.of("messages", m);
    }
}
