package com.odel.student.client;

import com.odel.common.internal.InternalClient;
import com.odel.student.dto.NotificationDtos.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** student-service → notification-service: every email goes through it. */
@Component
public class NotificationClient {
    private static final InternalClient.Service S = InternalClient.Service.NOTIFICATION;
    private final InternalClient internal;

    public NotificationClient(InternalClient internal) {
        this.internal = internal;
    }

    @SuppressWarnings("unchecked")
    public UUID send(Message m) {
        Map<String, Object> r = internal.post(S, "/internal/messages", m, Map.class);
        Object id = r == null ? null : r.get("emailId");
        return id == null ? null : UUID.fromString(id.toString());
    }

    public List<UUID> sendAll(List<Message> messages) {
        if (messages.isEmpty()) return List.of();
        return internal.post(S, "/internal/messages/bulk", new BulkRequest(messages), BulkResponse.class).ids();
    }

    public List<Status> status(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return internal.post(S, "/internal/messages/status", new StatusRequest(ids), StatusResponse.class).items();
    }
}
