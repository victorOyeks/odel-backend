package com.odel.tenant.controller;

import com.odel.tenant.entity.Tenant;
import com.odel.tenant.repository.TenantRepository;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.files.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/** Branding before sign-in: each university's pages load their own name, colours and logo. */
@RestController
@RequestMapping("/api/v1/public")
public class PublicContextController {
    private final StorageService storage;
    private final OdelProperties props;
    private final TenantRepository tenants;

    public PublicContextController(StorageService storage, OdelProperties props, TenantRepository tenants) {
        this.storage = storage;
        this.props = props;
        this.tenants = tenants;
    }

    public record TenantBranding(UUID id, String slug, String name, String shortName, String primaryColor,
                                 String accentColor, String logoUrl, String googleDomain, String status) {}

    public record Context(String kind, TenantBranding tenant, boolean devSignIn) {}

    @GetMapping("/context")
    public Context context(HttpServletRequest request) {
        ResolvedHost host = TenantFilter.resolved(request);
        Tenant t = host.known() ? tenants.findById(host.tenantId()).orElse(null) : null;
        if (t == null) throw new ApiException(HttpStatus.NOT_FOUND, "unknown_host", "This web address is not set up on the platform.");
        return new Context(t.getKind().name(), branding(t), props.google().devStub());
    }

    public TenantBranding branding(Tenant t) {
        String logo = t.getLogoKey() == null ? null : storage.presignGet(t.getLogoKey(), Duration.ofHours(6));
        return new TenantBranding(t.getId(), t.getSlug(), t.getName(), t.getShortName(), t.getPrimaryColor(),
                t.getAccentColor(), logo, t.getGoogleDomain(), t.getStatus().name());
    }
}
