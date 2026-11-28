package com.odel.common.instrumentation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BodyLoggingFilterTest {
    @Test
    void bankAccountNumbersAreMasked() {
        String masked = BodyLoggingFilter.mask("{\"bankCode\":\"058\",\"accountNumber\":\"0123454521\",\"account_number\":1234567890,\"accountName\":\"WESLEY ODEL\"}");
        assertThat(masked).doesNotContain("0123454521").doesNotContain("1234567890").contains("\"accountName\":\"WESLEY ODEL\"");
    }

    @Test
    void passwordsStayMasked() {
        assertThat(BodyLoggingFilter.mask("{\"password\":\"Adeola2026\"}")).isEqualTo("{\"password\":\"***\"}");
    }
}
