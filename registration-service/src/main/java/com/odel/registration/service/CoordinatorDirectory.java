package com.odel.registration.service;

import com.odel.common.tenancy.TenantContext;
import com.odel.registration.client.AcademicClient;
import com.odel.registration.dto.PeerDtos.CentreCoordinator;
import com.odel.registration.dto.PeerDtos.Coordinators;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Who coordinates each centre in the current session (academic-service Staffing), cached for 20 seconds per university. */
@Service
public class CoordinatorDirectory {
    private final AcademicClient academic;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    record Cached(Coordinators value, long at) {}

    public CoordinatorDirectory(AcademicClient academic) {
        this.academic = academic;
    }

    public Coordinators all() {
        UUID t = TenantContext.require();
        Cached c = cache.get(t);
        if (c != null && System.currentTimeMillis() - c.at() < 20_000) return c.value();
        Coordinators v = academic.coordinators();
        if (v == null || v.items() == null) v = new Coordinators(null, List.of());
        cache.put(t, new Cached(v, System.currentTimeMillis()));
        return v;
    }

    /** centre id → coordinator user id (centres without one are left out). */
    public Map<UUID, UUID> byCentre() {
        Map<UUID, UUID> m = new HashMap<>();
        for (CentreCoordinator c : all().items()) if (c.userId() != null) m.put(c.centreId(), c.userId());
        return m;
    }

    public Optional<UUID> coordinatorOf(UUID centreId) {
        return centreId == null ? Optional.empty() : Optional.ofNullable(byCentre().get(centreId));
    }
}
