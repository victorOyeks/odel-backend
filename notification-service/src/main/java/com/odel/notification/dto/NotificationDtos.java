package com.odel.notification.dto;

import com.odel.notification.entity.Notification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** The bell and push-token bodies (/api/v1/notifications, /api/v1/push-tokens). */
public final class NotificationDtos {
    private NotificationDtos() {}

    public record Item(UUID id, String type, String title, String body, String link, Instant createdAt, Instant readAt) {
        public static Item of(Notification n) {
            return new Item(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(), n.getCreatedAt(), n.getReadAt());
        }
    }

    public record TokenRequest(@NotBlank @Size(max = 500) String token, String platform) {}
}
