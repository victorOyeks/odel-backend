package com.odel.content.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.content.config.ContentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Bunny Stream over its REST API (video.bunnycdn.com) and CDN. Keys only from the environment. */
public class BunnyVideoGateway implements VideoGateway {
    private static final Logger log = LoggerFactory.getLogger(BunnyVideoGateway.class);
    private final ContentProperties.Bunny cfg;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public BunnyVideoGateway(ContentProperties.Bunny cfg, ObjectMapper json) {
        this.cfg = cfg;
        this.json = json;
        if (blank(cfg.libraryId()) || blank(cfg.apiKey()) || blank(cfg.cdnHost()) || blank(cfg.tokenKey())) {
            throw new IllegalStateException("ODEL_VIDEO_MODE=bunny needs ODEL_BUNNY_LIBRARY_ID, ODEL_BUNNY_API_KEY, ODEL_BUNNY_CDN_HOST and ODEL_BUNNY_TOKEN_KEY");
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    @Override public String mode() { return "bunny"; }

    @Override public String libraryId() { return cfg.libraryId(); }

    private String videos() { return cfg.apiBase() + "/library/" + cfg.libraryId() + "/videos"; }

    @Override
    public String createVideo(String title) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(videos())).header("AccessKey", cfg.apiKey())
                    .header("Content-Type", "application/json").header("Accept", "application/json").timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("title", title)))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new VideoHostException("Bunny refused to create the video (" + res.statusCode() + ")");
            return json.readTree(res.body()).path("guid").asText();
        } catch (VideoHostException e) {
            throw e;
        } catch (Exception e) {
            throw new VideoHostException("Bunny could not be reached: " + e.getMessage());
        }
    }

    @Override
    public UploadTicket uploadTicket(String videoId, String title, String contentType, Instant expires) {
        long exp = expires.getEpochSecond();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("AuthorizationSignature", BunnyTokens.uploadSignature(cfg.libraryId(), cfg.apiKey(), exp, videoId));
        headers.put("AuthorizationExpire", Long.toString(exp));
        headers.put("VideoId", videoId);
        headers.put("LibraryId", cfg.libraryId());
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("filetype", contentType == null ? "video/mp4" : contentType);
        meta.put("title", title);
        return new UploadTicket("TUS", cfg.apiBase() + "/tusupload", headers, meta, expires);
    }

    @Override
    public VideoInfo get(String videoId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(videos() + "/" + videoId)).header("AccessKey", cfg.readKey())
                    .header("Accept", "application/json").timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return null;
            if (res.statusCode() / 100 != 2) throw new VideoHostException("Bunny answered " + res.statusCode());
            JsonNode v = json.readTree(res.body());
            String resolutions = v.path("availableResolutions").asText("");
            List<String> list = resolutions.isBlank() ? List.of() : Arrays.stream(resolutions.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            return new VideoInfo(v.path("guid").asText(videoId), v.path("status").asInt(), v.path("length").asInt(), list,
                    v.path("encodeProgress").asInt(), v.path("storageSize").asLong());
        } catch (VideoHostException e) {
            throw e;
        } catch (Exception e) {
            throw new VideoHostException("Bunny could not be reached: " + e.getMessage());
        }
    }

    @Override
    public void delete(String videoId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(videos() + "/" + videoId)).header("AccessKey", cfg.apiKey())
                    .timeout(Duration.ofSeconds(20)).DELETE().build();
            int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code / 100 != 2 && code != 404) log.warn("Bunny did not delete video {}: {}", videoId, code);
        } catch (Exception e) {
            log.warn("Bunny did not delete video {}: {}", videoId, e.getMessage());
        }
    }

    @Override
    public Links links(String videoId, List<String> qualities, Instant expires) {
        long exp = expires.getEpochSecond();
        Map<String, String> hls = new LinkedHashMap<>(), mp4 = new LinkedHashMap<>();
        for (String q : qualities) {
            hls.put(q, sign(videoId, q + "/video.m3u8", exp));
            mp4.put(q, sign(videoId, "play_" + q + ".mp4", exp));
        }
        return new Links(sign(videoId, "playlist.m3u8", exp), hls, mp4);
    }

    private String sign(String videoId, String file, long exp) {
        return BunnyTokens.signedUrl(cfg.cdnHost(), cfg.tokenFormat(), cfg.tokenKey(), videoId, file, exp);
    }

    @Override
    public String embedUrl(String videoId, Instant expires) {
        long exp = expires.getEpochSecond();
        return "https://iframe.mediadelivery.net/embed/" + cfg.libraryId() + "/" + videoId + "?token="
                + BunnyTokens.embedToken(cfg.tokenKey(), videoId, exp) + "&expires=" + exp;
    }

    @Override
    public OptionalLong mp4Size(String videoId, String quality) {
        try {
            String url = sign(videoId, "play_" + quality + ".mp4", Instant.now().plusSeconds(600).getEpochSecond());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(15)).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() != 200) return OptionalLong.empty();
            return res.headers().firstValueAsLong("Content-Length");
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public boolean downloadMp4(String videoId, String quality, Path dest) {
        try {
            String url = sign(videoId, "play_" + quality + ".mp4", Instant.now().plusSeconds(3600).getEpochSecond());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
            HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
            return res.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Could not download {} {}p: {}", videoId, quality, e.getMessage());
            return false;
        }
    }
}
