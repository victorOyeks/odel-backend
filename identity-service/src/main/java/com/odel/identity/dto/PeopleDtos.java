package com.odel.identity.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: People & roles (W-15, W-15b, W-15c, W-21) and the staff two-step reset. */
public final class PeopleDtos {
    private PeopleDtos() {}

    public record RoleIn(String role, String scopeType, UUID scopeId) {}

    public record InviteRequest(String fullName, String email, List<RoleIn> roles) {}

    public record RolesRequest(List<RoleIn> roles) {}

    public record ReasonRequest(String reason) {}

    public record RoleView(UUID id, String role, String roleLabel, String scopeType, UUID scopeId, String scopeLabel) {}

    public record PersonView(UUID id, String fullName, String email, String initials, String status, List<RoleView> roles, String scopeLabel,
                             boolean twoStepSetUp, Instant invitedAt, Instant acceptedAt, Instant lastSignInAt, Instant suspendedAt,
                             String suspendedReason) {}

    public record Counts(long total, long active, long invited, long suspended) {}

    public record PeoplePage(List<PersonView> items, int page, int size, long total, Counts counts) {}

    public record Place(String kind, String what, String session, boolean current) {}

    public record PersonDetail(PersonView person, List<Place> staffing) {}

    public record SuspendCheck(List<String> warnings, List<Place> staffing) {}

    public record UploadRequest(String fileName, String contentType, Long size) {}

    public record UploadResponse(String key, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {}

    public record CheckRequest(String key, String fileName) {}

    public record Problem(int row, String message) {}

    public record ImportCheck(UUID id, String fileName, String status, int totalRows, int readyRows, int problemRows, List<Problem> problems,
                              String summary, Integer invited) {}

    /** A ready bulk-invite row, kept in Redis between the check and the confirm. */
    public record PendingInvite(int row, String fullName, String email, List<RoleIn> roles) {}

    public record PendingImport(UUID id, String fileName, int totalRows, List<PendingInvite> ready, List<Problem> problems) {}
}
