package com.odel.identity.dto;

import java.time.Instant;
import java.util.UUID;

/** The university's audit log (gap from B1: sign-ins, role changes, setup changes). */
public final class AuditDtos {
    private AuditDtos() {}

    public record Actor(UUID id, String fullName, String role, String roleLabel) {}

    public record Row(UUID id, Instant at, String type, String action, String summary, Actor actor, String ip) {}
}
