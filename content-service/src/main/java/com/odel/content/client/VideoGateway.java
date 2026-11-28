package com.odel.content.client;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The video host (Bunny Stream; a fake for local work and tests). Uploads go from the browser straight to the host (TUS), and
 * students play from the host's CDN with signed, expiring links: no video passes through our servers on the way in or out.
 */
public interface VideoGateway {
    String mode();

    String libraryId();

    /** Creates an empty video at the host and returns its id. */
    String createVideo(String title);

    /** What the browser needs to upload straight to the host. */
    UploadTicket uploadTicket(String videoId, String title, String contentType, Instant expires);

    /** The host's own record of the video, or null when the host does not know it. */
    VideoInfo get(String videoId);

    void delete(String videoId);

    /** Signed playback links: the adaptive master playlist, and per quality its HLS playlist and MP4. */
    Links links(String videoId, List<String> qualities, Instant expires);

    /** The signed iframe player link (the host's own player), a fallback for the web app. */
    String embedUrl(String videoId, Instant expires);

    /** The exact size of a quality's MP4, when the host has one. */
    OptionalLong mp4Size(String videoId, String quality);

    /** Downloads a quality's MP4 (for making the audio file). False when it cannot. */
    boolean downloadMp4(String videoId, String quality, Path dest);

    record UploadTicket(String protocol, String endpoint, Map<String, String> headers, Map<String, String> metadata, Instant expiresAt) {}

    /** status as Bunny's video object: 0 created, 1 uploaded, 2 processing, 3 transcoding, 4 finished, 5 error, 6 upload failed. */
    record VideoInfo(String videoId, int status, int lengthSeconds, List<String> resolutions, int encodeProgress, long storageSize) {
        public boolean finished() { return status == 4; }

        public boolean failed() { return status == 5 || status == 6; }

        /** Students can watch: finished, or still transcoding but 360p is out already. */
        public boolean playable() { return finished() || (status == 3 && resolutions.contains("360p")); }
    }

    record Links(String master, Map<String, String> hls, Map<String, String> mp4) {}
}
