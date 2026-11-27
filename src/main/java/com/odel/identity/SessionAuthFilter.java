package com.odel.identity;

import com.odel.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the session cookie into a CurrentUser. A session only counts on the university host it was
 * created on, so a Wesley cookie replayed against another university's host is ignored.
 */
public class SessionAuthFilter extends OncePerRequestFilter {
    private final SessionStore store;
    private final RoleAssignmentRepository roles;

    public SessionAuthFilter(SessionStore store, RoleAssignmentRepository roles) {
        this.store = store;
        this.roles = roles;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = SessionCookies.read(request, SessionCookies.SESSION);
        var found = store.find(token);
        if (found.isPresent() && found.get().data().tenantId.equals(TenantContext.current())) {
            SessionData data = found.get().data();
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("STAGE_" + data.stage.name()));
            Role active = null;
            if (data.stage == SessionData.Stage.SIGNED_IN && data.activeRoleId != null) {
                active = roles.findById(data.activeRoleId)
                        .filter(r -> r.getUserId().equals(data.userId)).map(RoleAssignment::getRole).orElse(null);
                if (active != null) authorities.add(new SimpleGrantedAuthority("ROLE_" + active.name()));
            }
            SecurityContextHolder.getContext().setAuthentication(new CurrentUser(found.get().key(), data, active, authorities));
            boolean statusPoll = "GET".equals(request.getMethod()) && "/api/v1/session".equals(request.getRequestURI());
            if (!statusPoll) store.touch(found.get().key(), data);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
