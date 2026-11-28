package com.odel.registration.service;

import com.odel.common.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalScopeTest {
    final UUID akure = UUID.randomUUID(), lagos = UUID.randomUUID(), ibadan = UUID.randomUUID();
    final UUID ade = UUID.randomUUID(), lawal = UUID.randomUUID(), ngozi = UUID.randomUUID();
    final Map<UUID, UUID> coordinators = Map.of(akure, ade, lagos, lawal);

    @Test
    void coordinatorSeesAndDecidesOnlyTheirCentre() {
        assertThat(ApprovalScope.visibleCentres(Role.CENTRE_COORDINATOR, ade, coordinators)).containsExactly(akure);
        assertThat(ApprovalScope.canSee(Role.CENTRE_COORDINATOR, ade, akure, coordinators)).isTrue();
        assertThat(ApprovalScope.canDecide(Role.CENTRE_COORDINATOR, ade, akure, coordinators)).isTrue();
        assertThat(ApprovalScope.canSee(Role.CENTRE_COORDINATOR, ade, lagos, coordinators)).isFalse();
        assertThat(ApprovalScope.canDecide(Role.CENTRE_COORDINATOR, ade, lagos, coordinators)).isFalse();
        assertThat(ApprovalScope.canDecide(Role.CENTRE_COORDINATOR, ade, ibadan, coordinators)).as("a centre with no coordinator").isFalse();
        assertThat(ApprovalScope.canDecide(Role.CENTRE_COORDINATOR, ade, null, coordinators)).isFalse();
    }

    @Test
    void adminDecidesEverywhereDirectorOnlyReads() {
        assertThat(ApprovalScope.visibleCentres(Role.ODEL_ADMIN, ngozi, coordinators)).isNull();
        assertThat(ApprovalScope.canDecide(Role.ODEL_ADMIN, ngozi, ibadan, coordinators)).isTrue();
        assertThat(ApprovalScope.canSee(Role.ODEL_DIRECTOR, ngozi, lagos, coordinators)).isTrue();
        assertThat(ApprovalScope.canDecide(Role.ODEL_DIRECTOR, ngozi, lagos, coordinators)).isFalse();
    }

    @Test
    void otherRolesSeeNothing() {
        assertThat(ApprovalScope.visibleCentres(Role.LECTURER, ade, coordinators)).isEmpty();
        assertThat(ApprovalScope.canSee(Role.LECTURER, ade, akure, coordinators)).isFalse();
        assertThat(ApprovalScope.canDecide(Role.STUDENT, ade, akure, coordinators)).isFalse();
    }
}
