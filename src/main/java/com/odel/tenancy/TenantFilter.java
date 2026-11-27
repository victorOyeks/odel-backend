package com.odel.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the university from the browser's host (sent by the web proxy as X-Odel-Host) and puts it
 * in TenantContext for the request. Runs before security, so sign-in already knows the university.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantFilter extends OncePerRequestFilter {
    public static final String HOST_HEADER = "X-Odel-Host";
    private final HostResolver resolver;

    public TenantFilter(HostResolver resolver) {
        this.resolver = resolver;
    }

    public static String browserHost(HttpServletRequest request) {
        String h = request.getHeader(HOST_HEADER);
        if (h == null || h.isBlank()) h = request.getHeader("Host");
        return h;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ResolvedHost resolved = resolver.resolve(browserHost(request));
        request.setAttribute(ResolvedHost.REQUEST_ATTRIBUTE, resolved);
        if (resolved.known()) TenantContext.set(resolved.tenant().getId());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    public static ResolvedHost resolved(HttpServletRequest request) {
        Object o = request.getAttribute(ResolvedHost.REQUEST_ATTRIBUTE);
        return o instanceof ResolvedHost r ? r : new ResolvedHost(null, null);
    }
}
