package com.odel.common.security;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import java.time.Instant;
import java.util.UUID;

/** What we keep in Redis for one signed-in browser. The cookie holds only a random token. */
public class SessionData {
    public enum Stage { MFA_SETUP, MFA_REQUIRED, CHOOSE_ROLE, SIGNED_IN }

    public UUID userId;
    public UUID tenantId;
    public Stage stage;
    public UUID activeRoleId;
    /** The active role itself, so every service can check permissions without asking identity-service. */
    public Role activeRole;
    public int mfaAttempts;
    public String pendingSecretEnc;
    public Instant createdAt;
    public String ip;
    public String userAgent;
    /** How the person proved who they are before the two-step: "Google", "Microsoft" or "an emailed code" (for audit lines). */
    public String firstFactor;
}
