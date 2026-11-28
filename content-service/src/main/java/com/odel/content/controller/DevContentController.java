package com.odel.content.controller;

import com.odel.common.ApiException;
import com.odel.content.client.FakeVideoGateway;
import com.odel.content.client.VideoGateway;
import com.odel.content.service.ContentJobs;
import com.odel.content.service.VideoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Dev profile only: the fake video host's upload and outcomes, and running the background jobs now. */
@RestController
@RequestMapping("/api/v1/dev/content")
@ConditionalOnProperty(name = "odel.content.dev-endpoints", havingValue = "true")
public class DevContentController {
    private final VideoGateway videos;
    private final ContentJobs jobs;
    private final VideoService videoService;

    public DevContentController(VideoGateway videos, ContentJobs jobs, VideoService videoService) {
        this.videos = videos;
        this.jobs = jobs;
        this.videoService = videoService;
    }

    public record Simulate(String outcome, Integer lengthSeconds, Boolean webhook) {}

    FakeVideoGateway fake() {
        if (videos instanceof FakeVideoGateway f) return f;
        throw new ApiException(HttpStatus.CONFLICT, "not_fake", "Videos go to Bunny Stream here (ODEL_VIDEO_MODE=bunny): there is nothing to simulate.");
    }

    /** The stand-in for Bunny's TUS upload (the body is ignored). */
    @PutMapping("/videos/{videoId}/upload")
    public ResponseEntity<Void> upload(@PathVariable String videoId) {
        if (!fake().uploaded(videoId)) throw ApiException.notFound();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/videos/{videoId}/simulate")
    public Map<String, Object> simulate(@PathVariable String videoId, @RequestBody(required = false) Simulate body) {
        Simulate s = body == null ? new Simulate("READY", null, true) : body;
        return fake().simulate(videoId, s.outcome(), s.lengthSeconds(), s.webhook() == null || s.webhook());
    }

    /** Stand-ins for the CDN's files (no real media in fake mode). */
    @GetMapping("/videos/{videoId}/**")
    public ResponseEntity<String> media(@PathVariable String videoId) {
        fake();
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("Fake video " + videoId + ": no media in fake mode.");
    }

    @PostMapping("/jobs/run")
    public Map<String, Object> runJobs() {
        ContentJobs.RunResult r = jobs.runOnce();
        int rechecked = videoService.recheck(java.time.Instant.now().plusSeconds(3600));
        return Map.of("converted", r.converted(), "kept", r.kept(), "pages", r.pages(), "audio", r.audio(), "videosRechecked", rechecked);
    }
}
