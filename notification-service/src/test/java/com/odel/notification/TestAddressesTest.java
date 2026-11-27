package com.odel.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestAddressesTest {
    @Test
    void reservedDomainsAreTestAddresses() {
        assertThat(TestAddresses.isTest("amaka@odl.northbridge.test")).isTrue();
        assertThat(TestAddresses.isTest("x@mail.example.com")).isTrue();
        assertThat(TestAddresses.isTest("x@EXAMPLE.ORG")).isTrue();
        assertThat(TestAddresses.isTest("someone@gmail.com")).isFalse();
        assertThat(TestAddresses.isTest("ngozi.adebayo@wesleyuni.edu.ng")).isFalse();
    }
}
