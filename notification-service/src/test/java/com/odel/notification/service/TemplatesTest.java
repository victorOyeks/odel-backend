package com.odel.notification.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplatesTest {
    @Test
    void fillsValuesAndRefusesMissingOnes() {
        assertThat(Templates.render("Dear {{fullName}},", Map.of("fullName", "Ngozi"))).isEqualTo("Dear Ngozi,");
        assertThatThrownBy(() -> Templates.render("Dear {{fullName}},", Map.of())).hasMessageContaining("fullName");
    }

    @Test
    void aFallbackIsUsedOnlyWhenTheValueIsMissing() {
        String t = "{{signInHow|Sign in with your work account}} here:";
        assertThat(Templates.render(t, Map.of())).isEqualTo("Sign in with your work account here:");
        assertThat(Templates.render(t, Map.of("signInHow", "Sign in with your Microsoft work account")))
                .isEqualTo("Sign in with your Microsoft work account here:");
    }
}
