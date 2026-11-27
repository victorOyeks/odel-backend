package com.odel.notification.dto;

import com.odel.notification.service.MessageService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bulk sends and delivery status (/internal/messages/bulk, /internal/messages/status). */
public final class MessageDtos {
    private MessageDtos() {}

    public record BulkRequest(List<MessageService.Message> messages) {}

    public record BulkResponse(List<UUID> ids) {}

    public record StatusRequest(List<UUID> ids) {}

    public record Status(UUID id, String status, String error, Instant sentAt) {}

    public record StatusResponse(List<Status> items) {}
}
