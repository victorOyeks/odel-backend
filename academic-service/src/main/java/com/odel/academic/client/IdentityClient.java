package com.odel.academic.client;

import com.odel.academic.dto.PeerDtos.Staff;
import com.odel.academic.dto.PeerDtos.StaffSummary;
import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.internal.InternalClient;
import com.odel.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Staff from identity-service: the People & roles list staffing picks from, its numbers, and people's names for the audit log. */
@Component
public class IdentityClient {
    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);
    private final InternalClient internal;
    private final Map<UUID, CachedName> names = new ConcurrentHashMap<>();

    public IdentityClient(InternalClient internal) {
        this.internal = internal;
    }

    private record CachedName(String name, long at) {}

    private record UserView(UUID id, String fullName) {}

    /** Everyone on the staff list, or only those holding a role. */
    public List<Staff> staff(String role) {
        String q = role == null ? "" : "?role=" + role;
        List<Staff> list = internal.get(InternalClient.Service.IDENTITY, "/internal/staff" + q, new ParameterizedTypeReference<List<Staff>>() {});
        return list == null ? List.of() : list;
    }

    public Map<UUID, Staff> staffById() {
        return staff(null).stream().collect(Collectors.toMap(Staff::id, Function.identity()));
    }

    /** The People step's numbers; empty if identity-service does not answer. */
    public Optional<StaffSummary> staffSummary() {
        try {
            return Optional.ofNullable(internal.get(InternalClient.Service.IDENTITY, "/internal/staff/summary", StaffSummary.class));
        } catch (ApiException e) {
            log.warn("Staff summary unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The audit actor for the signed-in person (their name from identity-service, cached for five minutes). */
    public AuditService.Actor actor(CurrentUser cu) {
        CachedName c = names.get(cu.userId());
        String name;
        if (c != null && System.currentTimeMillis() - c.at() < 300_000) {
            name = c.name();
        } else {
            try {
                UserView u = internal.get(InternalClient.Service.IDENTITY, "/internal/users/" + cu.userId(), UserView.class);
                name = u.fullName();
                names.put(cu.userId(), new CachedName(name, System.currentTimeMillis()));
            } catch (ApiException e) {
                name = "Staff member";
            }
        }
        return new AuditService.Actor(cu.userId(), cu.tenantId(), name, cu.activeRole() == null ? null : cu.activeRole().name());
    }
}
