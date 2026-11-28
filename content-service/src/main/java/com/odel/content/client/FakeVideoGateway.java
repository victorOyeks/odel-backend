package com.odel.content.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory stand-in for Bunny Stream (ODEL_VIDEO_MODE=fake): videos are "uploaded" to /api/v1/dev/content/videos/{id}/upload, then
 * encode, finish or fail when told (or on their own after fake-ready-seconds), and each change posts Bunny's webhook to content-service.
 */
public class FakeVideoGateway implements VideoGateway {
    public static final String LIBRARY = "fake-library";
    private static final Logger log = LoggerFactory.getLogger(FakeVideoGateway.class);
    private final Map<String, Fake> videos = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final Clock clock;
    private final String selfUrl;
    private final int readySeconds;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fake-video");
        t.setDaemon(true);
        return t;
    });

    static final class Fake {
        final String id;
        final String title;
        volatile int status;          // Bunny's video status
        volatile int length = 600;
        volatile int progress;
        volatile boolean deleted;

        Fake(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    public FakeVideoGateway(ObjectMapper json, Clock clock, String selfUrl, int readySeconds) {
        this.json = json;
        this.clock = clock;
        this.selfUrl = selfUrl;
        this.readySeconds = readySeconds;
    }

    @Override public String mode() { return "fake"; }

    @Override public String libraryId() { return LIBRARY; }

    @Override
    public String createVideo(String title) {
        String id = UUID.randomUUID().toString();
        videos.put(id, new Fake(id, title));
        return id;
    }

    @Override
    public UploadTicket uploadTicket(String videoId, String title, String contentType, Instant expires) {
        return new UploadTicket("PUT", "/api/v1/dev/content/videos/" + videoId + "/upload", Map.of(), Map.of("title", title), expires);
    }

    @Override
    public VideoInfo get(String videoId) {
        Fake f = videos.get(videoId);
        if (f == null || f.deleted) return null;
        List<String> res = f.status == 4 ? List.of("240p", "360p", "480p") : f.status == 3 && f.progress >= 50 ? List.of("240p", "360p") : List.of();
        return new VideoInfo(f.id, f.status, f.status >= 3 ? f.length : 0, res, f.progress, f.status == 4 ? f.length * 120_000L : 0);
    }

    @Override
    public void delete(String videoId) {
        Fake f = videos.get(videoId);
        if (f != null) f.deleted = true;
    }

    @Override
    public Links links(String videoId, List<String> qualities, Instant expires) {
        long exp = expires.getEpochSecond();
        String base = "/api/v1/dev/content/videos/" + videoId + "/";
        Map<String, String> hls = new LinkedHashMap<>(), mp4 = new LinkedHashMap<>();
        for (String q : qualities) {
            hls.put(q, base + q + "/video.m3u8?expires=" + exp);
            mp4.put(q, base + "play_" + q + ".mp4?expires=" + exp);
        }
        return new Links(base + "playlist.m3u8?expires=" + exp, hls, mp4);
    }

    @Override
    public String embedUrl(String videoId, Instant expires) {
        return "/api/v1/dev/content/videos/" + videoId + "/embed?expires=" + expires.getEpochSecond();
    }

    @Override public OptionalLong mp4Size(String videoId, String quality) { return OptionalLong.empty(); }

    @Override public boolean downloadMp4(String videoId, String quality, Path dest) { return false; }

    // ---- what the dev endpoints drive

    /** The browser's upload arrived. Encoding starts; with fake-ready-seconds it finishes on its own. */
    public boolean uploaded(String videoId) {
        Fake f = videos.get(videoId);
        if (f == null || f.deleted) return false;
        f.status = 2;
        if (readySeconds > 0) timer.schedule(() -> { if (f.status == 2) simulate(videoId, "READY", null, true); }, readySeconds, TimeUnit.SECONDS);
        return true;
    }

    /** ENCODING (transcoding, half way), READY (finished) or FAILED; posts Bunny's webhook when asked. */
    public Map<String, Object> simulate(String videoId, String outcome, Integer lengthSeconds, boolean webhook) {
        Fake f = videos.computeIfAbsent(videoId, id -> new Fake(id, "Video"));
        if (lengthSeconds != null && lengthSeconds > 0) f.length = lengthSeconds;
        int hookStatus;
        switch (outcome == null ? "READY" : outcome.toUpperCase(Locale.ROOT)) {
            case "ENCODING" -> { f.status = 3; f.progress = 50; hookStatus = 4; }
            case "FAILED" -> { f.status = 5; f.progress = 0; hookStatus = 5; }
            default -> { f.status = 4; f.progress = 100; hookStatus = 3; }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("VideoLibraryId", LIBRARY);
        body.put("VideoGuid", videoId);
        body.put("Status", hookStatus);
        if (webhook) post(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoId", videoId);
        out.put("status", f.status);
        out.put("webhook", webhook ? body : null);
        return out;
    }

    private void post(Map<String, Object> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(selfUrl + "/api/v1/content/webhooks/bunny"))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("fake Bunny webhook {} → {}", body, res.statusCode());
        } catch (Exception e) {
            log.warn("fake Bunny webhook not delivered: {}", e.getMessage());
        }
    }

    public Instant now() { return clock.instant(); }
}
