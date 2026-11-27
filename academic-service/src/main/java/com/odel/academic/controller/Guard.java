package com.odel.academic.controller;

import com.odel.common.security.Access;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;

/** Who may do what in academic setup. */
final class Guard {
    private Guard() {}

    /** Changes: the ODeL admin. */
    static void write(CurrentUser cu) {
        Access.require(cu, Role.ODEL_ADMIN);
    }

    /** Setup screens: the ODeL admin and the director. */
    static void read(CurrentUser cu) {
        Access.require(cu, Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
    }

    /** Lists other screens need (sessions, structure, courses, centres): any university staff role. */
    static void staff(CurrentUser cu) {
        Access.require(cu, Role.MANAGEMENT, Role.ODEL_DIRECTOR, Role.ODEL_ADMIN, Role.CENTRE_COORDINATOR, Role.LECTURER,
                Role.FACILITATOR, Role.EXAM_OFFICER, Role.HOD, Role.DEAN, Role.REGISTRY, Role.COUNSELLOR);
    }
}
