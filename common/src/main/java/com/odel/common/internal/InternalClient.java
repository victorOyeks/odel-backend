package com.odel.common.internal;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.security.InternalKeyFilter;
import com.odel.common.tenancy.TenantContext;
import com.odel.common.tenancy.TenantFilter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Calls another service's /internal/** endpoints with the shared key, passing the current university along.
 * A 4xx from the other service comes back as the same ApiException; an unreachable service is a 503.
 */
@Component
public class InternalClient {
    private final RestClient http = RestClient.create();
    private final OdelProperties props;

    public InternalClient(OdelProperties props) {
        this.props = props;
    }

    public enum Service { IDENTITY, TENANT, NOTIFICATION }

    public <T> T get(Service s, String path, Class<T> type) {
        return call(() -> spec(http.get().uri(base(s) + path)).retrieve().body(type));
    }

    public <T> T get(Service s, String path, ParameterizedTypeReference<T> type) {
        return call(() -> spec(http.get().uri(base(s) + path)).retrieve().body(type));
    }

    public <T> T post(Service s, String path, Object body, Class<T> type) {
        return call(() -> spec(http.post().uri(base(s) + path)).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type));
    }

    public void delete(Service s, String path) {
        call(() -> spec(http.delete().uri(base(s) + path)).retrieve().toBodilessEntity());
    }

    @SuppressWarnings("unchecked")
    private <S extends RestClient.RequestHeadersSpec<?>> S spec(S spec) {
        spec.header(InternalKeyFilter.HEADER, props.internalApiKey());
        UUID tenant = TenantContext.current();
        if (!TenantContext.NONE.equals(tenant)) spec.header(TenantFilter.TENANT_ID_HEADER, tenant.toString());
        return spec;
    }

    private String base(Service s) {
        return switch (s) {
            case IDENTITY -> props.services().identity();
            case TENANT -> props.services().tenant();
            case NOTIFICATION -> props.services().notification();
        };
    }

    private <T> T call(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) throw ApiException.notFound();
            throw new ApiException(HttpStatus.valueOf(e.getStatusCode().value()), "upstream", e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", "Part of the platform is restarting. Try again in a minute.");
        }
    }
}
