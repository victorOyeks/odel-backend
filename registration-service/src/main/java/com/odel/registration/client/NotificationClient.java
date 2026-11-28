package com.odel.registration.client;

import com.odel.common.internal.InternalClient;
import com.odel.registration.dto.PeerDtos.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** registration-service → notification-service: every email and bell item. A failed hand-over is logged, never fatal. */
@Component
public class NotificationClient {
    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);
    private final InternalClient internal;

    public NotificationClient(InternalClient internal) {
        this.internal = internal;
    }

    public void send(Message m) {
        try {
            internal.post(InternalClient.Service.NOTIFICATION, "/internal/messages", m, Map.class);
        } catch (RuntimeException e) {
            log.warn("message {} to {} not queued: {}", m.templateKey(), m.email(), e.getMessage());
        }
    }
}
