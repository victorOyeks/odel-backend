package com.odel.student.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileRulesTest {
    @Test
    void statesAreMatchedLoosely() {
        assertThat(ProfileRules.state("ondo")).isEqualTo("Ondo");
        assertThat(ProfileRules.state("Lagos State")).isEqualTo("Lagos");
        assertThat(ProfileRules.state("Abuja")).isEqualTo("FCT");
        assertThat(ProfileRules.state("akwa ibom")).isEqualTo("Akwa Ibom");
        assertThat(ProfileRules.state("Accra")).isNull();
        assertThat(ProfileRules.state(null)).isNull();
    }

    @Test
    void masksAsL14ShowsThem() {
        assertThat(ProfileRules.maskPhone("0803 412 4412")).isEqualTo("0803 *** 4412");
        assertThat(ProfileRules.maskEmail("adeola.okafor@gmail.com")).isEqualTo("adeola.o•••@gmail.com");
        assertThat(ProfileRules.maskEmail("ab@x.com")).isEqualTo("a•••@x.com");
        assertThat(ProfileRules.maskEmail(null)).isNull();
    }

    @Test
    void namesAllowLettersHyphensApostrophes() {
        String[] p = new String[1];
        assertThat(ProfileRules.name("  Okafor ", 100, false, p)).isEqualTo("Okafor");
        assertThat(ProfileRules.name("Adé-Ọlá O'Neil", 100, false, p)).isEqualTo("Adé-Ọlá O'Neil");
        assertThat(ProfileRules.name("Ok4for", 100, false, p)).isNull();
        assertThat(p[0]).contains("letters");
        p[0] = null;
        assertThat(ProfileRules.name("", 100, false, p)).isNull();
        assertThat(ProfileRules.name("", 150, true, p)).isEmpty();
        assertThat(ProfileRules.name("x".repeat(101), 100, false, p)).isNull();
    }

    @Test
    void dateOfBirthMustGiveAnAgeOf14To100() {
        LocalDate today = LocalDate.of(2026, 11, 28);
        String[] p = new String[1];
        assertThat(ProfileRules.dateOfBirth("2003-04-17", today, p)).isEqualTo(LocalDate.of(2003, 4, 17));
        assertThat(ProfileRules.dateOfBirth("2020-01-01", today, p)).isNull();
        assertThat(p[0]).contains("would make you 6");
        assertThat(ProfileRules.dateOfBirth("17/04/2003", today, p)).isNull();
    }

    @Test
    void genderAndLines() {
        assertThat(ProfileRules.gender("female")).isEqualTo("F");
        assertThat(ProfileRules.gender("m")).isEqualTo("M");
        assertThat(ProfileRules.gender("x")).isNull();
        assertThat(ProfileRules.addressLine("14 Oyemekun Road", "Akure", "Ondo")).isEqualTo("14 Oyemekun Road, Akure, Ondo");
        assertThat(ProfileRules.addressLine(null, null, null)).isNull();
        assertThat(ProfileRules.nextOfKinLine("Mrs Chioma Okafor", "Mother", "0805 111 2233")).isEqualTo("Mrs Chioma Okafor (Mother) · 0805 111 2233");
        assertThat(ProfileRules.fieldLabel("SURNAME")).isEqualTo("Surname");
        assertThat(ProfileRules.fieldLabel("NEXT_OF_KIN")).isEqualTo("Next of kin");
        assertThat(ProfileRules.size(421888)).isEqualTo("412 KB");
        assertThat(ProfileRules.size(1_900_000)).isEqualTo("1.8 MB");
    }
}
