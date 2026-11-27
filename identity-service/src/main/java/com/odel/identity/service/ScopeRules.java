package com.odel.identity.service;

import com.odel.common.security.Role;
import com.odel.common.security.ScopeType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Which roles an ODeL admin may give, and the scopes each role can have (W-15b). Pure, unit-tested. */
public final class ScopeRules {
    private ScopeRules() {}

    public static final Set<Role> GIVABLE = Set.of(Role.LECTURER, Role.FACILITATOR, Role.EXAM_OFFICER, Role.CENTRE_COORDINATOR, Role.HOD,
            Role.DEAN, Role.ODEL_DIRECTOR, Role.ODEL_ADMIN, Role.MANAGEMENT, Role.REGISTRY, Role.COUNSELLOR);

    private static final List<ScopeType> TEACHING = List.of(ScopeType.UNIVERSITY, ScopeType.FACULTY, ScopeType.DEPARTMENT, ScopeType.COURSE);

    public static List<ScopeType> allowed(Role role) {
        return switch (role) {
            case LECTURER, FACILITATOR, EXAM_OFFICER -> TEACHING;
            case CENTRE_COORDINATOR -> List.of(ScopeType.CENTRE);
            case HOD -> List.of(ScopeType.DEPARTMENT);
            case DEAN -> List.of(ScopeType.FACULTY);
            default -> List.of(ScopeType.UNIVERSITY);
        };
    }

    /** A role by its name (LECTURER) or its label (Lecturer, "Centre coordinator"); empty if it cannot be given here. */
    public static Optional<Role> role(String s) {
        if (s == null) return Optional.empty();
        String v = s.trim();
        for (Role r : GIVABLE) {
            if (r.name().equalsIgnoreCase(v.replace(' ', '_')) || r.label().equalsIgnoreCase(v)) return Optional.of(r);
        }
        return Optional.empty();
    }

    public static Optional<ScopeType> scope(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        try {
            return Optional.of(ScopeType.valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static final Map<ScopeType, String> SCOPE_NAME = Map.of(ScopeType.FACULTY, "a faculty", ScopeType.DEPARTMENT, "a department",
            ScopeType.COURSE, "a course", ScopeType.CENTRE, "a study centre", ScopeType.UNIVERSITY, "the whole university");

    /** "Lecturer for CSC 201 · Computer Science" or just "ODeL admin". */
    public static String invitationLabel(Role role, ScopeType scope, String scopeLabel) {
        return scope == ScopeType.UNIVERSITY || scopeLabel == null ? role.label() : role.label() + " for " + scopeLabel;
    }
}
