package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.VideoGateway;
import com.odel.content.client.VideoHostException;
import com.odel.content.dto.ContentDtos.*;
import com.odel.content.entity.Item;
import com.odel.content.entity.Offering;
import com.odel.content.entity.Week;
import com.odel.content.repository.ItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Uploads (T-04, T-04b, T-03l): the API only hands out signed links. Videos go from the browser to Bunny Stream by TUS; documents go
 * from the browser to storage. The browser then says the upload is done and the item moves on (ready, processing or converting).
 */
@Service
public class UploadService {
    static final Duration FILE_LINK = Duration.ofHours(1);
    static final Duration VIDEO_LINK = Duration.ofHours(24);
    private final Directory directory;
    private final AccessService access;
    private final BuilderService builder;
    private final ItemRepository items;
    private final VideoGateway videos;
    private final FileStore store;
    private final Lifecycle lifecycle;
    private final AuditService audit;
    private final Clock clock;

    public UploadService(Directory directory, AccessService access, BuilderService builder, ItemRepository items, VideoGateway videos, FileStore store,
                         Lifecycle lifecycle, AuditService audit, Clock clock) {
        this.directory = directory;
        this.access = access;
        this.builder = builder;
        this.items = items;
        this.videos = videos;
        this.store = store;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.clock = clock;
    }

    static ApiException hostDown() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "video_host_unavailable",
                "We could not reach the video service. Nothing was uploaded; try again in a minute.");
    }

    static void checkIn(UploadIn in) {
        if (in == null || in.fileName() == null || in.fileName().isBlank()) throw ApiException.badRequest("invalid", "Choose a file to upload.");
        if (in.sizeBytes() == null) throw ApiException.badRequest("invalid", "We need the file's size. Choose the file again.");
    }

    @Transactional
    public UploadStarted start(CurrentUser cu, UUID weekId, UploadIn in) {
        Week w = builder.week(weekId);
        Offering o = directory.offering(w.getOfferingId());
        access.edit(cu, o);
        checkIn(in);
        Item.Kind kind = FileKinds.kindOf(in.fileName());
        FileKinds.checkSize(kind, in.sizeBytes());
        Item i = newUpload(cu, o, w, kind, in);
        i.setPosition(items.lastPosition(w.getId()) + 1);
        Upload up = ticket(i, in.contentType());
        items.save(i);
        audit.record(new AuditService.Entry(TenantContext.require(), access.actor(cu), "CONTENT_ITEM_ADDED", "CONTENT", i.getId(),
                o.getCourseCode() + " week " + w.getNumber() + ": upload of \"" + i.getFileName() + "\" started", null, null));
        return new UploadStarted(builder.staffItem(i, null), up);
    }

    Item newUpload(CurrentUser cu, Offering o, Week w, Item.Kind kind, UploadIn in) {
        Instant now = clock.instant();
        Item i = new Item();
        i.setId(UUID.randomUUID());
        i.setOfferingId(o.getId());
        i.setWeekId(w.getId());
        i.setKind(kind);
        i.setTitle(in.title() != null && !in.title().isBlank() ? in.title().trim() : Labels.titleFromFile(in.fileName()));
        if (i.getTitle().length() > 200) i.setTitle(i.getTitle().substring(0, 200));
        i.setStatus(Item.Status.UPLOADING);
        i.setFileName(in.fileName().replaceAll("^.*[/\\\\]", ""));
        i.setContentType(FileKinds.contentType(in.fileName(), in.contentType()));
        i.setOriginalSizeBytes(in.sizeBytes());
        i.setCreatedBy(cu.userId());
        i.setCreatedByName(access.actor(cu).name());
        i.setCreatedAt(now);
        i.setUpdatedAt(now);
        return i;
    }

    /** New upload details: a new Bunny video when there is none, the same one while its TUS upload can resume. */
    Upload ticket(Item i, String contentType) {
        Instant now = clock.instant();
        if (i.getKind() == Item.Kind.VIDEO) {
            if (i.getVideoId() == null) {
                try {
                    i.setVideoId(videos.createVideo(i.getTitle()));
                } catch (VideoHostException e) {
                    throw hostDown();
                }
            }
            VideoGateway.UploadTicket t = videos.uploadTicket(i.getVideoId(), i.getTitle(), i.getContentType(), now.plus(VIDEO_LINK));
            return new Upload(t.protocol(), "PUT".equals(t.protocol()) ? t.endpoint() : null, "PUT".equals(t.protocol()) ? "PUT" : "POST",
                    t.endpoint(), t.headers(), t.metadata(), t.expiresAt());
        }
        String key = FileStore.key(TenantContext.require(), i.getOfferingId(), i.getId(), "original." + FileKinds.extension(i.getFileName()));
        i.setOriginalKey(key);
        String url = store.uploadLink(key, FILE_LINK);
        return new Upload("PUT", url, "PUT", null, Map.of("Content-Type", i.getContentType()), null, now.plus(FILE_LINK));
    }

    /** The item the browser's upload belongs to: a replacement waiting for its file, else the item itself. */
    Item target(Item i) {
        return items.replacementsOf(i.getId()).stream().filter(r -> r.getStatus() == Item.Status.UPLOADING).findFirst().orElse(i);
    }

    @Transactional
    public StaffItem uploaded(CurrentUser cu, UUID id) {
        Item shown = builder.item(id);
        Offering o = directory.offering(shown.getOfferingId());
        access.edit(cu, o);
        Item t = target(shown);
        if (t.getStatus() != Item.Status.UPLOADING) return builder.staffItem(shown, "Already uploaded");
        Instant now = clock.instant();
        t.setUploadedAt(now);
        t.setUpdatedAt(now);
        t.setFailureCode(null);
        t.setFailureMessage(null);
        String message;
        if (t.getKind() == Item.Kind.VIDEO) {
            t.setStatus(Item.Status.PROCESSING);
            message = "Uploaded. Preparing it for low data: students can watch it once it is ready, usually within 20 minutes.";
        } else {
            OptionalLong size = store.size(t.getOriginalKey());
            if (size.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "upload_missing", "We could not find the uploaded file. Upload it again.");
            t.setOriginalSizeBytes(size.getAsLong());
            if (t.getKind() == Item.Kind.SLIDES || t.getKind() == Item.Kind.NOTES) {
                t.setStatus(Item.Status.CONVERTING);
                message = "Uploaded. Converting it to a smaller PDF for students.";
            } else {
                t.setStorageKey(t.getOriginalKey());
                t.setSizeBytes(size.getAsLong());
                t.setStatus(Item.Status.READY);
                t.setReadyAt(now);
                message = "Uploaded and ready for students";
            }
        }
        items.flush();
        Item now2 = lifecycle.finished(t);
        return builder.staffItem(now2.getReplacesItemId() == null ? now2 : shown, message);
    }

    @Transactional
    public UploadStarted again(CurrentUser cu, UUID id) {
        Item shown = builder.item(id);
        Offering o = directory.offering(shown.getOfferingId());
        access.edit(cu, o);
        Item t = items.replacementsOf(shown.getId()).stream().findFirst().orElse(shown);
        if (t.getStatus() != Item.Status.UPLOADING && t.getStatus() != Item.Status.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "already_uploaded", "This file is already uploaded. Use Replace file to upload a new one.");
        }
        if (t.getKind() == Item.Kind.VIDEO && t.getStatus() == Item.Status.FAILED && t.getVideoId() != null) {
            String old = t.getVideoId();
            t.setVideoId(null);
            items.flush();
            builder.releaseVideo(old);
        }
        t.setStatus(Item.Status.UPLOADING);
        t.setFailureCode(null);
        t.setFailureMessage(null);
        t.setUpdatedAt(clock.instant());
        Upload up = ticket(t, t.getContentType());
        return new UploadStarted(builder.staffItem(shown, null), up);
    }

    @Transactional
    public UploadStarted replace(CurrentUser cu, UUID id, UploadIn in) {
        Item i = builder.item(id);
        Offering o = directory.offering(i.getOfferingId());
        access.edit(cu, o);
        if (i.getKind() == Item.Kind.LINK || i.getKind() == Item.Kind.PAGE) throw ApiException.badRequest("not_a_file", "Edit a link or page instead of replacing it.");
        checkIn(in);
        Item.Kind kind = FileKinds.kindOf(in.fileName());
        if ((kind == Item.Kind.VIDEO) != (i.getKind() == Item.Kind.VIDEO)) {
            throw ApiException.badRequest("wrong_kind", i.getKind() == Item.Kind.VIDEO ? "Replace a video with another video. Add other files with Upload files."
                    : "Replace a file with another document or audio file. Add videos with Upload files.");
        }
        FileKinds.checkSize(kind, in.sizeBytes());
        for (Item old : items.replacementsOf(i.getId())) {
            items.delete(old);
            items.flush();
            builder.releaseVideo(old.getVideoId());
        }
        Week w = builder.week(i.getWeekId());
        Item r = newUpload(cu, o, w, kind, new UploadIn(in.fileName(), in.contentType(), in.sizeBytes(), i.getTitle()));
        r.setTitle(i.getTitle());
        r.setReplacesItemId(i.getId());
        r.setHidden(true);
        r.setPosition(0);
        Upload up = ticket(r, in.contentType());
        items.save(r);
        items.flush();
        audit.record(new AuditService.Entry(TenantContext.require(), access.actor(cu), "CONTENT_ITEM_REPLACED", "CONTENT", i.getId(),
                o.getCourseCode() + ": new file \"" + r.getFileName() + "\" for \"" + i.getTitle() + "\" started", null, null));
        return new UploadStarted(builder.staffItem(i, "Students keep the current file until the new one is ready"), up);
    }
}
