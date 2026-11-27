package com.odel.identity;

import java.time.Instant;
import java.util.UUID;

/** What we keep in Redis for one signed-in browser. The cookie holds only a random token. */
public class SessionData {
    public enum Stage { MFA_SETUP, MFA_REQUIRED, CHOOSE_ROLE, SIGNED_IN }

    public UUID userId;
    public UUID tenantId;
    public Stage stage;
    public UUID activeRoleId;
    public int mfaAttempts;
    public String pendingSecretEnc;
    public Instant createdAt;
    public String ip;
    public String userAgent;
}
