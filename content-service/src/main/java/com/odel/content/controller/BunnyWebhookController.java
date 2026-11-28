package com.odel.content.controller;

import com.odel.content.service.VideoService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Bunny Stream's encoding webhook. Public (no session, no CSRF): the body is never trusted as it comes; the video is looked up through
 * Bunny's API and only that answer moves the item on.
 */
@RestController
public class BunnyWebhookController {
    private final VideoService videos;

    public BunnyWebhookController(VideoService videos) {
        this.videos = videos;
    }

    @PostMapping("/api/v1/content/webhooks/bunny")
    public Map<String, Object> webhook(@RequestBody(required = false) Map<String, Object> body) {
        return Map.of("received", true, "changed", videos.webhook(body));
    }
}
