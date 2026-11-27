package com.odel.notification;

import com.odel.common.tenancy.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Other services send messages through here (X-Internal-Key; the university in X-Odel-Tenant-Id). */
@RestController
@RequestMapping("/internal")
public class NotificationInternalController {
    private final MessageService messages;

    public NotificationInternalController(MessageService messages) {
        this.messages = messages;
    }

    @PostMapping("/messages")
    public ResponseEntity<Map<String, Object>> send(@RequestBody MessageService.Message message) {
        TenantContext.require();
        UUID emailId = messages.send(message);
        return ResponseEntity.ok(emailId == null ? Map.of() : Map.of("emailId", emailId));
    }
}
