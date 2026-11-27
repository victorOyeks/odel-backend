package com.odel.student.client;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.security.InternalKeyFilter;
import com.odel.common.tenancy.TenantContext;
import com.odel.common.tenancy.TenantFilter;
import com.odel.student.dto.AcademicLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Asks academic-service (port 9304) for the university's programmes, centres and current session. */
@Component
@ConditionalOnProperty(name = "odel.student.academic-lookup", havingValue = "http", matchIfMissing = true)
public class HttpAcademicDirectory implements AcademicDirectory {
    private final RestClient http;
    private final String key;

    public HttpAcademicDirectory(@Value("${odel.student.academic-url:http://127.0.0.1:9304}") String base, OdelProperties props) {
        this.http = RestClient.builder().baseUrl(base).build();
        this.key = props.internalApiKey();
    }

    @Override
    public AcademicLookup lookup() {
        try {
            AcademicLookup l = http.get().uri("/internal/academic/lookup").header(InternalKeyFilter.HEADER, key)
                    .header(TenantFilter.TENANT_ID_HEADER, TenantContext.require().toString()).retrieve().body(AcademicLookup.class);
            return l == null ? AcademicLookup.empty() : l;
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
                    "We could not read the programmes and centres just now. Try again in a minute.");
        }
    }
}
