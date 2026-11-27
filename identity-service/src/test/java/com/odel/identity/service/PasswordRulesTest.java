package com.odel.identity.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordRulesTest {
    @Test
    void acceptsEightCharactersWithALetterAndANumber() {
        assertThat(PasswordRules.check("adeola2026", "adeola2026")).isEmpty();
        assertThat(PasswordRules.check("Abcdefg1", "Abcdefg1")).isEmpty();
    }

    @Test
    void explainsWhatIsMissing() {
        assertThat(PasswordRules.check("", "")).containsEntry("password", "Enter a password.");
        assertThat(PasswordRules.check("abc1", "abc1")).containsEntry("password", "Use at least 8 characters.");
        assertThat(PasswordRules.check("12345678", "12345678")).containsEntry("password", "Add at least one letter.");
        assertThat(PasswordRules.check("abcdefgh", "abcdefgh")).containsEntry("password", "Add at least one number.");
        assertThat(PasswordRules.check("a1".repeat(65), "a1".repeat(65))).containsEntry("password", "Use at most 128 characters.");
    }

    @Test
    void confirmationMustMatch() {
        assertThat(PasswordRules.check("adeola2026", "adeola2027")).containsEntry("confirmPassword", "The two passwords do not match.");
        assertThat(PasswordRules.check("adeola2026", null)).containsKey("confirmPassword");
    }

    @Test
    void rulesAreShownToTheStudent() {
        assertThat(PasswordRules.RULES).extracting(PasswordRules.Rule::key).containsExactly("length", "letter", "number");
        assertThat(PasswordRules.HINT).contains("8 characters");
    }
}
