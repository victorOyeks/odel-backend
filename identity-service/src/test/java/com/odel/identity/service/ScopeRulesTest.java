package com.odel.identity.service;

import com.odel.common.security.Role;
import com.odel.common.security.ScopeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeRulesTest {
    @Test
    void rolesByNameOrLabel() {
        assertThat(ScopeRules.role("Lecturer")).contains(Role.LECTURER);
        assertThat(ScopeRules.role("centre coordinator")).contains(Role.CENTRE_COORDINATOR);
        assertThat(ScopeRules.role("EXAM_OFFICER")).contains(Role.EXAM_OFFICER);
        assertThat(ScopeRules.role("SUPER_ADMIN")).isEmpty();
        assertThat(ScopeRules.role("Student")).isEmpty();
    }

    @Test
    void scopesPerRole() {
        assertThat(ScopeRules.allowed(Role.LECTURER)).contains(ScopeType.COURSE, ScopeType.DEPARTMENT);
        assertThat(ScopeRules.allowed(Role.CENTRE_COORDINATOR)).containsExactly(ScopeType.CENTRE);
        assertThat(ScopeRules.allowed(Role.HOD)).containsExactly(ScopeType.DEPARTMENT);
        assertThat(ScopeRules.allowed(Role.ODEL_ADMIN)).containsExactly(ScopeType.UNIVERSITY);
    }

    @Test
    void invitationLabels() {
        assertThat(ScopeRules.invitationLabel(Role.LECTURER, ScopeType.COURSE, "CSC 201 · Computer Science")).isEqualTo("Lecturer for CSC 201 · Computer Science");
        assertThat(ScopeRules.invitationLabel(Role.ODEL_ADMIN, ScopeType.UNIVERSITY, "Wesley University")).isEqualTo("ODeL admin");
    }
}
