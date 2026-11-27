package com.odel.student.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** notification-service's /internal/messages, /bulk and /status. */
public final class NotificationDtos {
    private NotificationDtos() {}

    public record TenantLook(String name, String shortName, String primaryColor) {}

    public record Message(UUID userId, String email, String templateKey, Map<String, ?> vars, String link, TenantLook tenant) {}

    public record BulkRequest(List<Message> messages) {}

    public record BulkResponse(List<UUID> ids) {}

    public record StatusRequest(List<UUID> ids) {}

    public record Status(UUID id, String status, String error, Instant sentAt) {}

    public record StatusResponse(List<Status> items) {}
}
