package com.odel.content.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LabelsTest {
    @Test
    void plainLabels() {
        assertThat(Labels.day(LocalDate.of(2027, 2, 15))).isEqualTo("Mon 15 Feb");
        assertThat(Labels.size(1_258_291L)).isEqualTo("1.2 MB");
        assertThat(Labels.size(44_040_192L)).isEqualTo("42 MB");
        assertThat(Labels.size(900L * 1024)).isEqualTo("900 KB");
        assertThat(Labels.length(1084)).isEqualTo("18 min");
        assertThat(Labels.clock(422)).isEqualTo("7:02");
        assertThat(Labels.initials("Dr Funmi Bello")).isEqualTo("FB");
        assertThat(Labels.titleFromFile("lecture-06_stacks.mp4")).isEqualTo("Lecture 06 stacks");
        assertThat(Labels.at(Instant.parse("2027-02-09T08:12:00Z"))).isEqualTo("Tue 9 Feb · 9:12 am");
        assertThat(Labels.ago(Instant.parse("2027-01-24T10:00:00Z"), LocalDate.of(2027, 2, 9))).isEqualTo("24 Jan · 16 days");
        assertThat(Labels.perMinute(2_411_724L * 18, 18 * 60)).isEqualTo("about 2.3 MB a minute");
    }
}
