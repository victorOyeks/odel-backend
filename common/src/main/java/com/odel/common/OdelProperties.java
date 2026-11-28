package com.odel.common;

import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("odel")
public record OdelProperties(
        String baseDomain,
        /** The provider console's host; defaults to console.<base-domain>. */
        String consoleHost,
        /** Shared secret for service-to-service calls to /internal/**. */
        String internalApiKey,
        Services services,
        String webScheme,
        String timeZone,
        Session session,
        Mfa mfa,
        Google google,
        Storage storage,
        Mail mail,
        Push push,
        Jobs jobs,
        Bootstrap bootstrap) {

    /** Where each service listens, for /internal/** calls. */
    public record Services(String identity, String tenant, String notification, String academic, String students, String payments) {}

    public record Session(Duration idleTimeout, Duration signInTimeout, Duration absoluteTimeout,
                          boolean secureCookies, int trustDeviceDays) {}

    public record Mfa(String encryptionKey, String issuerSuffix) {}

    public record Google(String clientId, String clientSecret, String redirectUri, boolean devStub) {}

    public record Storage(String mode, String localDir, String endpoint, String region, String bucket, String accessKey, String secretKey) {}

    public record Mail(String mode, String brevoApiKey, String fromAddress, String fromName) {}

    public record Push(String fcmProjectId, String fcmServiceAccountFile) {}

    public record Jobs(boolean workerEnabled, Duration pollInterval) {}

    public record Bootstrap(String superAdminEmail, String superAdminName) {}
}
