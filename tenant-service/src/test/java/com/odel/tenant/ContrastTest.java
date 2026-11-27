package com.odel.tenant;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContrastTest {
    @Test
    void wesleyNavyPassesAndPaleYellowFails() {
        assertThat(Contrast.ratio("#1A2368", "#FFFFFF")).isGreaterThan(Contrast.MIN);
        assertThat(Contrast.ratio("#0F6E6E", "#FFFFFF")).isGreaterThan(Contrast.MIN); // Crestfield teal
        assertThat(Contrast.ratio("#F5D76E", "#FFFFFF")).isLessThan(Contrast.MIN);
        assertThat(Contrast.ratio("#000000", "#FFFFFF")).isEqualTo(21.0);
    }
}
