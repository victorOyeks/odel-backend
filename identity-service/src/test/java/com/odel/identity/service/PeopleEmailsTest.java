package com.odel.identity.service;

import com.odel.identity.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PeopleEmailsTest {
    private static User withEmail(String email) {
        User u = new User();
        u.setEmail(email);
        return u;
    }

    @Test
    void studentAccountsWithoutAnEmailAreSkipped() {
        // An imported student with no email has a null email; the staff invite checks must not fail on them (they answered 500).
        assertThat(PeopleService.emailsOf(List.of(withEmail("F.Bello@WesleyUni.edu.ng"), withEmail(null), withEmail("k.ola@wesleyuni.edu.ng"))))
                .containsExactlyInAnyOrder("f.bello@wesleyuni.edu.ng", "k.ola@wesleyuni.edu.ng");
    }
}
