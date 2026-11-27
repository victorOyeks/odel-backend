package com.odel.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("odel")
public record OdelProperties(
        String baseDomain,
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

    public record Session(Duration idleTimeout, Duration signInTimeout, Duration absoluteTimeout,
                          boolean secureCookies, int trustDeviceDays) {}

    public record Mfa(String encryptionKey, String issuerSuffix) {}

    public record Google(String clientId, String clientSecret, String redirectUri, boolean devStub) {}

    public record Storage(String mode, String localDir, String endpoint, String region, String bucket, String accessKey, String secretKey) {}

    public record Mail(String mode, String fromAddress, String fromName) {}

    public record Push(String fcmProjectId, String fcmServiceAccountFile) {}

    public record Jobs(boolean workerEnabled, Duration pollInterval) {}

    public record Bootstrap(String superAdminEmail, String superAdminName) {}
}
