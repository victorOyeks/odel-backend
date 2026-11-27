package com.odel.student.client;

import com.odel.common.ApiException;
import com.odel.common.internal.InternalClient;
import com.odel.student.dto.AcademicLookup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Asks academic-service (port 9304, GET /internal/academic/reference) for the programmes, centres and current session. */
@Component
@ConditionalOnProperty(name = "odel.student.academic-lookup", havingValue = "http", matchIfMissing = true)
public class HttpAcademicDirectory implements AcademicDirectory {
    private final InternalClient internal;

    public HttpAcademicDirectory(InternalClient internal) {
        this.internal = internal;
    }

    @Override
    public AcademicLookup lookup() {
        try {
            AcademicLookup l = internal.get(InternalClient.Service.ACADEMIC, "/internal/academic/reference", AcademicLookup.class);
            if (l == null) return AcademicLookup.empty();
            return new AcademicLookup(l.currentSession(), l.programmes() == null ? java.util.List.of() : l.programmes(),
                    l.centres() == null ? java.util.List.of() : l.centres());
        } catch (ApiException e) {
            if (e.status() == HttpStatus.SERVICE_UNAVAILABLE) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
                        "We could not read the programmes and centres just now. Try again in a minute.");
            }
            throw e;
        }
    }
}
