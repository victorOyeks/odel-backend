package com.odel.identity.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileFormatTest {
    @Test
    void devicesInPlainWords() {
        assertThat(ProfileFormat.device("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"))
                .isEqualTo("Chrome on Windows");
        assertThat(ProfileFormat.device("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"))
                .isEqualTo("Safari on iPhone");
        assertThat(ProfileFormat.device("Mozilla/5.0 (Linux; Android 13; SM-A145F) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"))
                .isEqualTo("Chrome on Android");
        assertThat(ProfileFormat.device("okhttp/4.12.0 (Android 13)")).isEqualTo("Android phone");
        assertThat(ProfileFormat.device("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/131.0 Safari/537.36 Edg/131.0")).isEqualTo("Edge on Windows");
        assertThat(ProfileFormat.device(null)).isEqualTo("A browser");
        assertThat(ProfileFormat.device("Java-http-client/21")).isEqualTo("A browser");
    }

    @Test
    void summaryAndMasks() {
        assertThat(ProfileFormat.summary(List.of())).isEqualTo("This browser only");
        assertThat(ProfileFormat.summary(List.of("Android phone"))).isEqualTo("This browser and an Android phone");
        assertThat(ProfileFormat.summary(List.of("Chrome on Windows"))).isEqualTo("This browser and Chrome on Windows");
        assertThat(ProfileFormat.summary(List.of("A browser"))).isEqualTo("This browser and a browser");
        assertThat(ProfileFormat.summary(List.of("A", "B"))).isEqualTo("This browser and 2 other devices");
        assertThat(ProfileFormat.maskIp("102.89.34.7")).isEqualTo("102.89.•.•");
        assertThat(ProfileFormat.maskIp(null)).isNull();
        assertThat(ProfileFormat.when(Instant.parse("2026-11-28T13:14:00Z"))).isEqualTo("Sat 28 Nov at 2:14 pm");
    }
}
