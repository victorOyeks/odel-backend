package com.odel.identity.service;

import com.odel.common.security.StaffSignIn;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaffEmailRulesTest {
    @Test
    void onlyAddressesExactlyOnTheStaffDomainCount() {
        assertThat(StaffEmailRules.onDomain("ngozi.adebayo@wesleyuni.edu.ng", "wesleyuni.edu.ng")).isTrue();
        assertThat(StaffEmailRules.onDomain(" Ngozi.Adebayo@WesleyUni.EDU.ng ", "wesleyuni.edu.ng")).isTrue();
        assertThat(StaffEmailRules.onDomain("x@wesleyuni.edu.ng", "@wesleyuni.edu.ng")).isTrue();
        assertThat(StaffEmailRules.onDomain("x@mail.wesleyuni.edu.ng", "wesleyuni.edu.ng")).isFalse();
        assertThat(StaffEmailRules.onDomain("x@notwesleyuni.edu.ng", "wesleyuni.edu.ng")).isFalse();
        assertThat(StaffEmailRules.onDomain("x@wesleyuni.edu.ng.evil.com", "wesleyuni.edu.ng")).isFalse();
        assertThat(StaffEmailRules.onDomain("someone@gmail.com", "wesleyuni.edu.ng")).isFalse();
        assertThat(StaffEmailRules.onDomain("wesleyuni.edu.ng", "wesleyuni.edu.ng")).isFalse();
        assertThat(StaffEmailRules.onDomain(null, "wesleyuni.edu.ng")).isFalse();
        // the provider console has no staff domain
        assertThat(StaffEmailRules.onDomain("chuka.obi@odel.dev", null)).isTrue();
    }

    @Test
    void addressesAreComparedInLowerCase() {
        assertThat(StaffEmailRules.normalise("  B.Ade@WesleyUni.edu.ng ")).isEqualTo("b.ade@wesleyuni.edu.ng");
        assertThat(StaffEmailRules.looksLikeEmail("b.ade")).isFalse();
        assertThat(StaffEmailRules.looksLikeEmail("b.ade@wesleyuni.edu.ng")).isTrue();
    }

    @Test
    void signInMethodsReadAndExplainThemselves() {
        assertThat(StaffSignIn.parse("microsoft")).contains(StaffSignIn.MICROSOFT);
        assertThat(StaffSignIn.parse("email-code")).contains(StaffSignIn.EMAIL_CODE);
        assertThat(StaffSignIn.parse("zoho")).isEmpty();
        assertThat(StaffSignIn.orGoogle(null)).isEqualTo(StaffSignIn.GOOGLE);
        assertThat(StaffSignIn.MICROSOFT.how("wesleyuni.edu.ng"))
                .isEqualTo("Sign in with your Microsoft work account (your @wesleyuni.edu.ng address)");
        assertThat(StaffSignIn.EMAIL_CODE.how("wesleyuni.edu.ng"))
                .isEqualTo("Sign in with a code sent to your work email (your @wesleyuni.edu.ng address)");
        assertThat(StaffSignIn.GOOGLE.label()).isEqualTo("Google Workspace");
    }
}
