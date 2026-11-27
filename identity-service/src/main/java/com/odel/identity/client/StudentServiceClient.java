package com.odel.identity.client;

import com.odel.common.ApiException;
import com.odel.common.internal.InternalClient;
import com.odel.identity.dto.StudentAuthDtos.StudentContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** identity-service → student-service (/internal/students/**): the student's centre for E-03, and "activated". */
@Component
public class StudentServiceClient {
    private static final Logger log = LoggerFactory.getLogger(StudentServiceClient.class);
    private final InternalClient internal;

    public StudentServiceClient(InternalClient internal) {
        this.internal = internal;
    }

    /** Best effort: the screens still work without it. */
    public Optional<StudentContact> contact(UUID userId) {
        try {
            return Optional.ofNullable(internal.get(InternalClient.Service.STUDENTS, "/internal/students/contact?userId=" + userId, StudentContact.class));
        } catch (ApiException e) {
            log.warn("Student contact for {} not available: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Part of activation: if student-service cannot be told, the activation is rolled back and the student tries again. */
    public void activated(UUID userId, Instant at, String consentVersion, Instant consentAt) {
        internal.post(InternalClient.Service.STUDENTS, "/internal/students/activated",
                Map.of("userId", userId, "activatedAt", at, "consentVersion", consentVersion, "consentAt", consentAt), Void.class);
    }
}
