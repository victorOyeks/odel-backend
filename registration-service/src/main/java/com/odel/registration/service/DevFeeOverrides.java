package com.odel.registration.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dev profile only (PUT /api/v1/dev/registration/fee-status): one student's fee answer, kept in memory. */
@Component
public class DevFeeOverrides {
    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    public void set(UUID tenantId, UUID studentId, UUID semesterId, String status) {
        overrides.put(key(tenantId, studentId, semesterId), status);
    }

    public void clear(UUID tenantId, UUID studentId, UUID semesterId) {
        overrides.remove(key(tenantId, studentId, semesterId));
    }

    public Optional<String> get(UUID tenantId, UUID studentId, UUID semesterId) {
        return Optional.ofNullable(overrides.get(key(tenantId, studentId, semesterId)));
    }

    private static String key(UUID t, UUID s, UUID sem) {
        return t + "/" + s + "/" + sem;
    }
}
