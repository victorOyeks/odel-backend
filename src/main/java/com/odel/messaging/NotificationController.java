package com.odel.messaging;

import com.odel.common.ApiException;
import com.odel.common.PageResponse;
import com.odel.identity.CurrentUserArg;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The bell and push sign-up. Everyone only ever sees and changes their own items. */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private final NotificationRepository notifications;
    private final PushTokenRepository pushTokens;
    private final Clock clock;

    public NotificationController(NotificationRepository notifications, PushTokenRepository pushTokens, Clock clock) {
        this.notifications = notifications;
        this.pushTokens = pushTokens;
        this.clock = clock;
    }

    public record Item(UUID id, String type, String title, String body, String link, Instant createdAt, Instant readAt) {
        static Item of(Notification n) {
            return new Item(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(), n.getCreatedAt(), n.getReadAt());
        }
    }

    @GetMapping("/notifications")
    @Transactional(readOnly = true)
    public PageResponse<Item> list(CurrentUserArg.Holder cu, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        var p = notifications.findByUserIdOrderByCreatedAtDesc(cu.user().userId(), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(p, Item::of);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unread(CurrentUserArg.Holder cu) {
        return Map.of("count", notifications.countByUserIdAndReadAtIsNull(cu.user().userId()));
    }

    @PostMapping("/notifications/{id}/read")
    @Transactional
    public ResponseEntity<Void> read(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Notification n = notifications.findById(id).filter(x -> x.getUserId().equals(cu.user().userId()))
                .orElseThrow(ApiException::notFound);
        if (n.getReadAt() == null) n.setReadAt(clock.instant());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    @Transactional
    public ResponseEntity<Void> readAll(CurrentUserArg.Holder cu) {
        notifications.markAllRead(cu.user().userId(), clock.instant());
        return ResponseEntity.noContent().build();
    }

    public record TokenRequest(@NotBlank @Size(max = 500) String token, String platform) {}

    @PostMapping("/push-tokens")
    @Transactional
    public ResponseEntity<Void> register(CurrentUserArg.Holder cu, @Valid @RequestBody TokenRequest body) {
        Instant now = clock.instant();
        PushToken t = pushTokens.findByToken(body.token()).orElseGet(() -> {
            PushToken n = new PushToken();
            n.setId(UUID.randomUUID());
            n.setToken(body.token());
            n.setCreatedAt(now);
            return n;
        });
        t.setUserId(cu.user().userId());
        t.setPlatform(body.platform() == null ? "WEB" : body.platform());
        t.setLastSeenAt(now);
        pushTokens.save(t);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/push-tokens")
    @Transactional
    public ResponseEntity<Void> unregister(CurrentUserArg.Holder cu, @Valid @RequestBody TokenRequest body) {
        pushTokens.findByToken(body.token()).filter(t -> t.getUserId().equals(cu.user().userId())).ifPresent(pushTokens::delete);
        return ResponseEntity.noContent().build();
    }
}
