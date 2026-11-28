package com.odel.registration.service;

import com.odel.common.security.Role;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who may see and decide a request: an ODeL admin (every centre), the director (sees every centre, decides none), a centre
 * coordinator only for the centres they coordinate in the current session. Pure, so it is unit tested.
 */
public final class ApprovalScope {
    private ApprovalScope() {}

    /** The centres the person may see; null = every centre. Empty = none. */
    public static Set<UUID> visibleCentres(Role role, UUID userId, Map<UUID, UUID> coordinatorByCentre) {
        if (role == Role.ODEL_ADMIN || role == Role.ODEL_DIRECTOR) return null;
        if (role == Role.CENTRE_COORDINATOR) {
            return coordinatorByCentre.entrySet().stream().filter(e -> userId.equals(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toSet());
        }
        return Set.of();
    }

    public static boolean canSee(Role role, UUID userId, UUID centreId, Map<UUID, UUID> coordinatorByCentre) {
        Set<UUID> v = visibleCentres(role, userId, coordinatorByCentre);
        return v == null || (centreId != null && v.contains(centreId));
    }

    public static boolean canDecide(Role role, UUID userId, UUID centreId, Map<UUID, UUID> coordinatorByCentre) {
        if (role == Role.ODEL_ADMIN) return true;
        return role == Role.CENTRE_COORDINATOR && centreId != null && userId.equals(coordinatorByCentre.get(centreId));
    }
}
