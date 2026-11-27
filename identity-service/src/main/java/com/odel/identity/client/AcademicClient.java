package com.odel.identity.client;

import com.odel.common.ApiException;
import com.odel.common.internal.InternalClient;
import com.odel.identity.dto.PeopleDtos.Place;
import com.odel.identity.dto.StaffDtos.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** academic-service: scope labels for role assignments, and what a person is assigned to. */
@Component
public class AcademicClient {
    private final InternalClient internal;

    public AcademicClient(InternalClient internal) {
        this.internal = internal;
    }

    /** Empty when the faculty/department/course/centre is not one of this university's. */
    public Optional<Scope> scope(String type, UUID id) {
        try {
            return Optional.ofNullable(internal.get(InternalClient.Service.ACADEMIC, "/internal/academic/scopes/" + type + "/" + id, Scope.class));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }

    public Optional<Scope> resolve(String type, String code) {
        try {
            return Optional.ofNullable(internal.get(InternalClient.Service.ACADEMIC, "/internal/academic/scopes/resolve?scopeType=" + type
                    + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8), Scope.class));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }

    private record Places(List<Place> items) {}

    /** What the person teaches or coordinates; empty if academic-service does not answer. */
    public List<Place> staffing(UUID userId) {
        try {
            Places p = internal.get(InternalClient.Service.ACADEMIC, "/internal/academic/staffing/users/" + userId, Places.class);
            return p == null || p.items() == null ? List.of() : p.items();
        } catch (ApiException e) {
            return List.of();
        }
    }
}
