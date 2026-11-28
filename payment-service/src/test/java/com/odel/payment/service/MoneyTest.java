package com.odel.payment.service;

import com.odel.payment.client.FakeGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    void labels() {
        assertThat(Money.label(1_500_000)).isEqualTo("₦15,000");
        assertThat(Money.exact(1_500_000)).isEqualTo("₦15,000.00");
        assertThat(Money.label(32_550)).isEqualTo("₦325.50");
        assertThat(Money.ngn(1_500_000)).isEqualTo("NGN 15,000.00");
        assertThat(Money.label(4_518_000_000L)).isEqualTo("₦45,180,000");
        assertThat(Money.naira(1_500_000)).isEqualByComparingTo("15000");
        assertThat(Money.kobo(new BigDecimal("15000"))).isEqualTo(1_500_000);
    }

    @Test
    void theSplit() {
        // ₦15,000 fee, ₦3,000 to the university's ODeL centre: the provider's flat share (Paystack transaction_charge) is ₦12,000.
        assertThat(Money.providerShare(1_500_000, 300_000)).isEqualTo(1_200_000);
        assertThat(Money.providerShare(1_500_000, 0)).isEqualTo(1_500_000);
        assertThatThrownBy(() -> Money.providerShare(1_500_000, 1_600_000)).isInstanceOf(IllegalArgumentException.class);
        // Paystack's charge (1.5% + ₦100, capped at ₦2,000) comes out of the provider's share (bearer=account): ₦325 on ₦15,000.
        long fee = FakeGateway.fees(1_500_000);
        assertThat(fee).isEqualTo(32_500);
        assertThat(Money.providerShare(1_500_000, 300_000) - fee).as("provider's net").isEqualTo(1_167_500);
        assertThat(FakeGateway.fees(50_000_000)).as("capped").isEqualTo(200_000);
        // The story's totals: 3,012 paid → ₦36,144,000 and ₦9,036,000.
        assertThat(Money.label(3012L * Money.providerShare(1_500_000, 300_000))).isEqualTo("₦36,144,000");
        assertThat(Money.label(3012L * 300_000)).isEqualTo("₦9,036,000");
    }

    @Test
    void references() {
        assertThat(References.prefix("Wesley University")).isEqualTo("WU");
        assertThat(References.prefix("Wesley University (wes-pay-1x)")).isEqualTo("WU");
        assertThat(References.prefix("Federal University of Technology, Akure")).isEqualTo("FUTA");
        assertThat(References.payment("Wesley University")).matches("WU-PF-\\d{6}");
        assertThat(References.refund()).matches("RF-\\d{5}");
    }
}
