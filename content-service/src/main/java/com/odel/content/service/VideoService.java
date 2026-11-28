package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.VideoGateway;
import com.odel.content.client.VideoGateway.VideoInfo;
import com.odel.content.client.VideoHostException;
import com.odel.content.config.ContentProperties;
import com.odel.content.entity.Item;
import com.odel.content.repository.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The video life cycle after the upload: Bunny's webhook (never trusted as it comes: the video is looked up through the API), the
 * 2-minute re-check of videos still processing, ready (sizes per quality, audio file queued, uploader told) or failed.
 */
@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    static final String FAILED_MESSAGE = "Bunny could not prepare this video. Upload it again, or export it as an MP4 (H.264) first.";
    private final VideoGateway videos;
    private final ItemRepository items;
    private final Lifecycle lifecycle;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ContentProperties props;
    private final Clock clock;

    public VideoService(VideoGateway videos, ItemRepository items, Lifecycle lifecycle, JdbcTemplate jdbc, TransactionTemplate tx,
                        ContentProperties props, Clock clock) {
        this.videos = videos;
        this.items = items;
        this.lifecycle = lifecycle;
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    record Ref(UUID id, UUID tenantId) {}

    /** Bunny's webhook: {"VideoLibraryId","VideoGuid","Status"}. Returns how many items it moved on. */
    public int webhook(Map<String, Object> body) {
        Object lib = body == null ? null : body.get("VideoLibraryId");
        Object guid = body == null ? null : body.get("VideoGuid");
        if (lib == null || !String.valueOf(lib).equals(videos.libraryId())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_library", "This webhook is not for our video library.");
        }
        if (guid == null) return 0;
        List<Ref> refs = jdbc.query("select id, tenant_id from items where video_id = ?",
                (rs, n) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), String.valueOf(guid));
        int changed = 0;
        VideoInfo info;
        try {
            info = refs.isEmpty() ? null : videos.get(String.valueOf(guid));
        } catch (VideoHostException e) {
            log.warn("Bunny webhook for {}: could not check the video ({}); the re-check will", guid, e.getMessage());
            return 0;
        }
        if (info == null) return 0;
        for (Ref r : refs) {
            Boolean c = TenantContext.runAs(r.tenantId(), () -> tx.execute(s -> apply(r.id(), info)));
            if (Boolean.TRUE.equals(c)) changed++;
        }
        return changed;
    }

    /** Moves one item on from the host's answer. True when its status changed. */
    Boolean apply(UUID itemId, VideoInfo info) {
        Item i = items.findInTenant(itemId).orElse(null);
        if (i == null || i.getKind() != Item.Kind.VIDEO) return false;
        Instant now = clock.instant();
        i.setLastCheckedAt(now);
        Item.Status before = i.getStatus();
        if (info.failed()) {
            if (before == Item.Status.FAILED || before == Item.Status.READY) return false;
            i.setStatus(Item.Status.FAILED);
            i.setFailureCode("video_failed");
            i.setFailureMessage(FAILED_MESSAGE);
            i.setUpdatedAt(now);
            items.flush();
            lifecycle.tellUploader(i, "content.video_failed");
            return true;
        }
        if (info.playable()) {
            int length = Math.max(1, info.lengthSeconds());
            Map<String, Long> sizes = new LinkedHashMap<>();
            for (String q : Views.QUALITIES) {
                if (!info.resolutions().contains(q)) continue;
                long size = videos.mp4Size(i.getVideoId(), q).orElse(Views.estimate(q, length));
                sizes.put(q, size);
            }
            boolean sizesChanged = !Objects.equals(new LinkedHashMap<>(i.qualityMap()), sizes);
            i.qualityMap(sizes);
            i.setDurationSeconds(length);
            i.setEncodeProgress(info.encodeProgress());
            if (before == Item.Status.READY) {
                if (sizesChanged) i.setUpdatedAt(now);
                return false;
            }
            i.setStatus(Item.Status.READY);
            i.setReadyAt(now);
            i.setUpdatedAt(now);
            i.setFailureCode(null);
            i.setFailureMessage(null);
            if (i.getAudioStatus() == null) i.setAudioStatus(Item.AudioStatus.PENDING);
            items.flush();
            lifecycle.tellUploader(i, "content.video_ready");
            lifecycle.finished(i);
            return true;
        }
        i.setEncodeProgress(info.encodeProgress());
        if (before == Item.Status.UPLOADING && info.status() >= 1) {
            i.setStatus(Item.Status.PROCESSING);
            if (i.getUploadedAt() == null) i.setUploadedAt(now);
            i.setUpdatedAt(now);
            return true;
        }
        return false;
    }

    /** Every 2 minutes: videos processing for over 3 minutes are asked about; uploads older than a day are failed. */
    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT1M")
    public void tick() {
        if (!props.jobsEnabled()) return;
        try {
            recheck(clock.instant());
        } catch (Exception e) {
            log.warn("Video re-check failed: {}", e.getMessage(), e);
        }
    }

    public synchronized int recheck(Instant now) {
        int moved = 0;
        List<Ref> due = jdbc.query("""
                select id, tenant_id from items where kind = 'VIDEO' and status in ('PROCESSING', 'UPLOADING') and video_id is not null
                  and coalesce(uploaded_at, created_at) <= ? and (last_checked_at is null or last_checked_at <= ?) order by created_at limit 100""",
                (rs, n) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                Timestamp.from(now.minus(Duration.ofMinutes(3))), Timestamp.from(now.minus(Duration.ofMinutes(2))));
        for (Ref r : due) {
            Boolean c = TenantContext.runAs(r.tenantId(), () -> tx.execute(s -> {
                Item i = items.findInTenant(r.id()).orElse(null);
                if (i == null) return false;
                VideoInfo info;
                try {
                    info = videos.get(i.getVideoId());
                } catch (VideoHostException e) {
                    i.setLastCheckedAt(now);
                    return false;
                }
                if (info != null && apply(i.getId(), info)) return true;
                i.setLastCheckedAt(now);
                return expire(i, now);
            }));
            if (Boolean.TRUE.equals(c)) moved++;
        }
        // Documents whose upload never finished.
        List<Ref> stale = jdbc.query("select id, tenant_id from items where kind <> 'VIDEO' and status = 'UPLOADING' and created_at <= ? limit 100",
                (rs, n) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), Timestamp.from(now.minus(Duration.ofHours(24))));
        for (Ref r : stale) {
            Boolean c = TenantContext.runAs(r.tenantId(), () -> tx.execute(s -> items.findInTenant(r.id()).map(i -> expire(i, now)).orElse(false)));
            if (Boolean.TRUE.equals(c)) moved++;
        }
        return moved;
    }

    boolean expire(Item i, Instant now) {
        if (i.getStatus() != Item.Status.UPLOADING || i.getCreatedAt().isAfter(now.minus(Duration.ofHours(24)))) return false;
        i.setStatus(Item.Status.FAILED);
        i.setFailureCode("upload_incomplete");
        i.setFailureMessage("The upload did not finish. Upload the file again.");
        i.setUpdatedAt(now);
        return true;
    }
}
