package com.odel.notification.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MailAllowListTest {
    @Test
    void emptyListAllowsEveryone() {
        MailAllowList none = new MailAllowList("");
        assertThat(none.restricted()).isFalse();
        assertThat(none.allows("ngozi.adebayo@wesleyuni.edu.ng")).isTrue();
    }

    @Test
    void onlyListedDomainsAndTheirSubdomains() {
        MailAllowList dev = new MailAllowList(" gmail.com, @OyeksGlobal.com ,");
        assertThat(dev.restricted()).isTrue();
        assertThat(dev.allows("oyeksglobal+wesley@gmail.com")).isTrue();
        assertThat(dev.allows("chuka.obi@oyeksglobal.com")).isTrue();
        assertThat(dev.allows("x@mail.oyeksglobal.com")).isTrue();
        assertThat(dev.allows("NGOZI.ADEBAYO@WESLEYUNI.EDU.NG")).isFalse();
        assertThat(dev.allows("someone@notgmail.com")).isFalse();
        assertThat(dev.allows("no-at-sign")).isFalse();
    }
}
