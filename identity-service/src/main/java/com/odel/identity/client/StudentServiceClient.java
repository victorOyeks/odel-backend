package com.odel.identity.client;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.security.InternalKeyFilter;
import com.odel.common.tenancy.TenantContext;
import com.odel.common.tenancy.TenantFilter;
import com.odel.identity.dto.StudentAuthDtos.StudentContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** identity-service → student-service (/internal/students/**): the student's centre for E-03, and "activated". */
@Component
public class StudentServiceClient {
    private static final Logger log = LoggerFactory.getLogger(StudentServiceClient.class);
    private final RestClient http;
    private final String key;

    public StudentServiceClient(@Value("${odel.student-service-url:${ODEL_STUDENT_URL:http://127.0.0.1:9305}}") String base, OdelProperties props) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.http = RestClient.builder().baseUrl(base).requestFactory(factory).build();
        this.key = props.internalApiKey();
    }

    /** Best effort: the screens still work without it. */
    public Optional<StudentContact> contact(UUID userId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/students/contact?userId={id}", userId)
                    .header(InternalKeyFilter.HEADER, key).header(TenantFilter.TENANT_ID_HEADER, TenantContext.require().toString())
                    .retrieve().body(StudentContact.class));
        } catch (Exception e) {
            log.warn("Student contact for {} not available: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Part of activation: if student-service cannot be told, the activation is rolled back and the student tries again. */
    public void activated(UUID userId, Instant at, String consentVersion, Instant consentAt) {
        try {
            http.post().uri("/internal/students/activated").contentType(MediaType.APPLICATION_JSON)
                    .header(InternalKeyFilter.HEADER, key).header(TenantFilter.TENANT_ID_HEADER, TenantContext.require().toString())
                    .body(Map.of("userId", userId, "activatedAt", at, "consentVersion", consentVersion, "consentAt", consentAt))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Could not tell student-service that {} activated: {}", userId, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", "Part of the platform is restarting. Try again in a minute.");
        }
    }
}
