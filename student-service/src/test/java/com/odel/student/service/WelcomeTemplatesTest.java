package com.odel.student.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WelcomeTemplatesTest {
    @Test
    void defaultIsValidAndFillsEveryField() {
        assertThat(WelcomeTemplates.check(WelcomeTemplates.DEFAULT_SUBJECT, WelcomeTemplates.DEFAULT_BODY)).isEmpty();
        String body = WelcomeTemplates.render(WelcomeTemplates.DEFAULT_BODY, WelcomeTemplates.examples());
        assertThat(body).startsWith("Welcome, Adeola").contains("WU/ODL/24/0153").contains("Akure Study Centre").doesNotContain("{");
    }

    @Test
    void unknownFieldsAndAMissingLinkAreRefused() {
        assertThat(WelcomeTemplates.check("Hi", "Hello {first_nam}, {activation_link}")).containsEntry("body", "Unknown field {first_nam}. Use one of the fields below.");
        assertThat(WelcomeTemplates.check("Hi", "Hello {first_name}")).containsKey("body");
        assertThat(WelcomeTemplates.check("", "{activation_link}")).containsKey("subject");
        assertThat(WelcomeTemplates.check("Hi {foo}", "{activation_link}")).containsKey("subject");
    }

    @Test
    void renderIsLiteral() {
        assertThat(WelcomeTemplates.render("Hi {first_name} {other}", Map.of("first_name", "$1 \\o/"))).isEqualTo("Hi $1 \\o/ {other}");
        assertThat(WelcomeTemplates.render("Hi { first_name }", Map.of("first_name", "Ade"))).isEqualTo("Hi Ade");
    }
}
