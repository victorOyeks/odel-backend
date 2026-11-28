package com.odel.payment.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayerEmailTest {
    final UUID id = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000");

    @Test
    void realEmailIsKept() {
        assertThat(PaymentService.payerEmail(" adeola.okafor@gmail.com ", id, "students.oyeksglobal.com")).isEqualTo("adeola.okafor@gmail.com");
    }

    @Test
    void missingOrReservedEmailUsesTheFallback() {
        for (String e : new String[]{null, "", "adeola@students.wesley.test", "x@y.invalid", "a@example.com", "b@mail.example.com", "c@localhost"}) {
            assertThat(PaymentService.payerEmail(e, id, "students.oyeksglobal.com")).as(String.valueOf(e)).isEqualTo("student-1a2b3c4d@students.oyeksglobal.com");
        }
    }
}
