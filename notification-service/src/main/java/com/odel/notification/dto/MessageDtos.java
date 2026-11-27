package com.odel.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bulk sends and delivery status (/internal/messages/bulk, /internal/messages/status). */
public final class MessageDtos {
    private MessageDtos() {}

    public record BulkRequest(List<Message> messages) {}

    public record BulkResponse(List<UUID> ids) {}

    public record StatusRequest(List<UUID> ids) {}

    public record Status(UUID id, String status, String error, Instant sentAt) {}

    public record StatusResponse(List<Status> items) {}

    /**
     * @param userId may be null for someone without an account yet (then only email goes)
     * @param link   where the bell item and push open, on the person's portal
     */
    public record Message(UUID userId, String email, String templateKey, Map<String, ?> vars, String link, TenantLook tenant) {}

    /** The university's name and colour, sent by the caller so emails carry its branding. */
    public record TenantLook(String name, String shortName, String primaryColor) {}
}
