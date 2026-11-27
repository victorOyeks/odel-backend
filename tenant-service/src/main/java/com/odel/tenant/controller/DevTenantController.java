package com.odel.tenant.controller;

import com.odel.tenant.entity.Tenant;
import com.odel.tenant.repository.TenantRepository;

import com.odel.common.ApiException;
import com.odel.common.internal.InternalClient;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Development only (dev sign-in on): a platform admin deletes a university that is still setting up, with everything
 * every service holds for it. Used by the live walkthrough test to clean up after itself. Never on production.
 */
@RestController
@ConditionalOnProperty(name = "odel.google.dev-stub", havingValue = "true")
public class DevTenantController {
    private final TenantRepository tenants;
    private final InternalClient internal;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DevTenantController(TenantRepository tenants, InternalClient internal, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.tenants = tenants;
        this.internal = internal;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @DeleteMapping("/api/v1/dev/tenants/{id}")
    public ResponseEntity<Void> delete(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.SUPER_ADMIN);
        Tenant t = tenants.findById(id).filter(x -> !x.isPlatform()).orElseThrow(ApiException::notFound);
        if (t.getStatus() != Tenant.Status.SETTING_UP) {
            throw new ApiException(HttpStatus.CONFLICT, "not_deletable", "Only a university that is still setting up can be deleted.");
        }
        TenantContext.runAs(t.getId(), () -> {
            internal.delete(InternalClient.Service.IDENTITY, "/internal/tenant-data");
            internal.delete(InternalClient.Service.NOTIFICATION, "/internal/tenant-data");
        });
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from app.audit_log where tenant_id = ?", t.getId());
            tenants.deleteById(t.getId());
        });
        return ResponseEntity.noContent().build();
    }
}
