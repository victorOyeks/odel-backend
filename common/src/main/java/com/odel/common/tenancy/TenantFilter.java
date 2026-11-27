package com.odel.common.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts the university the gateway resolved (X-Odel-Tenant-Id / -Kind) into TenantContext for the request.
 * Hibernate's @TenantId then filters every query by it: the one place tenant filtering happens.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantFilter extends OncePerRequestFilter {
    public static final String HOST_HEADER = "X-Odel-Host";
    public static final String TENANT_ID_HEADER = "X-Odel-Tenant-Id";
    public static final String TENANT_KIND_HEADER = "X-Odel-Tenant-Kind";

    public static String browserHost(HttpServletRequest request) {
        String h = request.getHeader(HOST_HEADER);
        if (h == null || h.isBlank()) h = request.getHeader("Host");
        return h;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID tenantId = null;
        String raw = request.getHeader(TENANT_ID_HEADER);
        if (raw != null && !raw.isBlank()) {
            try { tenantId = UUID.fromString(raw.trim()); } catch (IllegalArgumentException ignored) { }
        }
        ResolvedHost resolved = new ResolvedHost(browserHost(request), tenantId, request.getHeader(TENANT_KIND_HEADER));
        request.setAttribute(ResolvedHost.REQUEST_ATTRIBUTE, resolved);
        if (tenantId != null) TenantContext.set(tenantId);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    public static ResolvedHost resolved(HttpServletRequest request) {
        Object o = request.getAttribute(ResolvedHost.REQUEST_ATTRIBUTE);
        return o instanceof ResolvedHost r ? r : new ResolvedHost(null, null, null);
    }
}
