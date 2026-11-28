package com.odel.payment.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The student pays Paystack's charge (owner, 28 Nov 2026). Figures match what Paystack's test checkout showed for ₦15,000. */
class GatewayChargeTest {
    @Test
    void fifteenThousandBecomesWhatPaystackCharges() {
        assertThat(GatewayCharge.estimatedTotal(1_500_000)).isEqualTo(1_532_995); // ₦15,329.95, as Paystack's checkout and transfer account show
    }

    @Test
    void smallAmountsHaveNoFlatPartAndLargeOnesAreCapped() {
        assertThat(GatewayCharge.estimatedTotal(200_000)).isEqualTo(203_046); // ₦2,000 → ₦2,030.46 (1.5% grossed up, no ₦100)
        assertThat(GatewayCharge.estimatedTotal(20_000_000)).isEqualTo(20_200_000); // capped: ₦200,000 + ₦2,000
    }

    @Test
    void providerKeepsItsWholeShareWhenTheStudentCoveredTheFee() {
        // Paid ₦15,329.95; Paystack kept ₦329.95 (its fee on the gross): the student covered it, the provider nets ₦12,000.
        assertThat(GatewayCharge.studentCharge(1_500_000, 1_532_995L)).isEqualTo(32_995);
        assertThat(GatewayCharge.providerNet(1_200_000, 1_500_000, 1_532_995L, 32_995L)).isEqualTo(1_200_000);
        // Charge not passed on: the provider bears ₦325.
        assertThat(GatewayCharge.providerNet(1_200_000, 1_500_000, 1_500_000L, 32_500L)).isEqualTo(1_167_500);
        // Older rows without the charged amount behave like before.
        assertThat(GatewayCharge.providerNet(1_200_000, 1_500_000, null, 32_500L)).isEqualTo(1_167_500);
    }
}
