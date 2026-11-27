package com.odel.academic.service;

import com.odel.academic.dto.PeerDtos.RoleView;
import com.odel.academic.dto.PeerDtos.Staff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaffOptionsTest {
    private final UUID dept = UUID.randomUUID();
    private final List<Staff> everyone = List.of(
            new Staff(UUID.randomUUID(), "Ngozi Adebayo", "n@w.ng", "ACTIVE", List.of(new RoleView("ODEL_ADMIN", "UNIVERSITY", null, "Wesley University"))),
            new Staff(UUID.randomUUID(), "Dr Kemi Ola", "k@w.ng", "INVITED", List.of(new RoleView("LECTURER", "UNIVERSITY", null, "Wesley University"))),
            new Staff(UUID.randomUUID(), "Dr Funmi Bello", "f@w.ng", "ACTIVE", List.of(new RoleView("LECTURER", "DEPARTMENT", dept, "Computer Science"))),
            new Staff(UUID.randomUUID(), "Mr Gone", "g@w.ng", "SUSPENDED", List.of(new RoleView("LECTURER", "UNIVERSITY", null, "Wesley University"))));

    @Test
    void peopleWithoutTheRoleAndSuspendedPeopleAreLeftOut() {
        // Everyone is passed in (one call for several roles): someone without the role must not break the list.
        assertThat(StaffingService.build(everyone, "LECTURER", null)).extracting("fullName").containsExactly("Dr Funmi Bello", "Dr Kemi Ola");
        assertThat(StaffingService.build(everyone, "EXAM_OFFICER", null)).isEmpty();
    }

    @Test
    void theDepartmentsOwnStaffComeFirst() {
        assertThat(StaffingService.build(everyone, "LECTURER", dept).get(0).fullName()).isEqualTo("Dr Funmi Bello");
        assertThat(StaffingService.build(everyone, "LECTURER", dept).get(0).scopeLabel()).isEqualTo("Computer Science");
    }
}
