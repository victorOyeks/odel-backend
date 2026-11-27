package com.odel.identity;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

/** The authenticated browser session. Authorities: STAGE_<stage>, and ROLE_<active role> once signed in. */
public class CurrentUser extends AbstractAuthenticationToken {
    private final String sessionKey;
    private final SessionData data;
    private final Role activeRole;

    public CurrentUser(String sessionKey, SessionData data, Role activeRole, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.sessionKey = sessionKey;
        this.data = data;
        this.activeRole = activeRole;
        setAuthenticated(true);
    }

    public String sessionKey() { return sessionKey; }
    public SessionData data() { return data; }
    public UUID userId() { return data.userId; }
    public UUID tenantId() { return data.tenantId; }
    public Role activeRole() { return activeRole; }

    @Override public Object getCredentials() { return ""; }
    @Override public Object getPrincipal() { return data.userId; }
}
