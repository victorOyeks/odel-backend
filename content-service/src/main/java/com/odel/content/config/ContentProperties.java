package com.odel.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odel.content.*: the video host (fake or bunny) and Bunny's keys (environment only), the converters, where registration-service
 * listens, the fake video's auto-ready delay, the background jobs and the dev-only endpoints.
 */
@ConfigurationProperties("odel.content")
public record ContentProperties(String videoMode, Bunny bunny, String sofficePath, String ffmpegPath, String registrationUrl,
                                int fakeReadySeconds, boolean jobsEnabled, boolean devEndpoints) {
    public ContentProperties {
        if (videoMode == null || videoMode.isBlank()) videoMode = "fake";
        if (bunny == null) bunny = new Bunny(null, null, null, null, null, null, null);
        if (sofficePath == null || sofficePath.isBlank()) sofficePath = "soffice";
        if (ffmpegPath == null || ffmpegPath.isBlank()) ffmpegPath = "ffmpeg";
        if (registrationUrl == null || registrationUrl.isBlank()) registrationUrl = "http://127.0.0.1:9307";
    }

    public boolean fake() {
        return !"bunny".equalsIgnoreCase(videoMode);
    }

    public record Bunny(String libraryId, String cdnHost, String apiKey, String readOnlyApiKey, String tokenKey, String tokenFormat, String apiBase) {
        public Bunny {
            if (tokenFormat == null || tokenFormat.isBlank()) tokenFormat = "hs256";
            if (apiBase == null || apiBase.isBlank()) apiBase = "https://video.bunnycdn.com";
        }

        /** The key for lookups: the read-only key when set, else the library key. */
        public String readKey() {
            return readOnlyApiKey != null && !readOnlyApiKey.isBlank() ? readOnlyApiKey : apiKey;
        }
    }
}
