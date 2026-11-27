package com.odel.academic.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: What the other services answer (identity, students, tenant). */
public final class PeerDtos {
    private PeerDtos() {}

    public record RoleView(String role, String scopeType, UUID scopeId, String scopeLabel) {}

    public record Staff(UUID id, String fullName, String email, String status, List<RoleView> roles) {
        public boolean suspended() { return "SUSPENDED".equals(status); }

        public boolean has(String role) { return roles != null && roles.stream().anyMatch(r -> r.role().equals(role)); }
    }

    public record StaffSummary(long total, long invited, long active, long suspended, Map<String, Long> byRole) {}

    public record Welcome(long sent, long failed, long notSent) {}

    public record CentreCount(UUID centreId, long students) {}

    public record StudentsSummary(long total, long active, Instant lastImportAt, Welcome welcome, List<CentreCount> byCentre) {
        public static final StudentsSummary NONE = new StudentsSummary(0, 0, null, new Welcome(0, 0, 0), List.of());

        public long atCentre(UUID centreId) {
            return byCentre == null ? 0 : byCentre.stream().filter(c -> c.centreId().equals(centreId)).mapToLong(CentreCount::students).sum();
        }

        public Welcome welcomeOrZero() { return welcome == null ? new Welcome(0, 0, 0) : welcome; }
    }

    public record Tenant(UUID id, String name, String shortName, String googleDomain, String primaryColor, String portalUrl) {}
}
