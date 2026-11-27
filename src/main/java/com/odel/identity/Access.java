package com.odel.identity;

import com.odel.common.ApiException;

import java.util.Arrays;

/** Role checks against the role the person is acting in right now (the role switcher's choice). */
public final class Access {
    private Access() {}

    public static void require(CurrentUser cu, Role... allowed) {
        if (cu.activeRole() == null || Arrays.stream(allowed).noneMatch(r -> r == cu.activeRole())) throw ApiException.forbidden();
    }
}
