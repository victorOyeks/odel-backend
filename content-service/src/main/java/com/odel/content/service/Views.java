package com.odel.content.service;

import com.odel.content.dto.ContentDtos.*;
import com.odel.content.entity.Item;
import com.odel.content.entity.ItemProgress;
import com.odel.content.entity.Offering;
import com.odel.content.entity.Week;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** How items, weeks and offerings read on the staff and student screens. */
public final class Views {
    /** Bytes a second per quality when the host gives no exact size (360p ≈ 2.3 MB a minute, as the design says). */
    public static final Map<String, Long> BYTES_PER_SECOND = new LinkedHashMap<>(Map.of("240p", 26_214L, "360p", 40_195L, "480p", 69_905L));
    /** The audio file: 48 kbps mono AAC. */
    public static final long AUDIO_BYTES_PER_SECOND = 6_000L;
    public static final List<String> QUALITIES = List.of("240p", "360p", "480p");

    private Views() {}

    public static SemesterRef semester(Offering o) {
        return new SemesterRef(o.getSemesterId(), o.getSessionId(), o.getSessionName(), o.getSemesterNumber(), o.semesterLabel());
    }

    public static CourseRef course(Offering o) {
        return new CourseRef(o.getCourseId(), o.getCourseCode(), o.getCourseTitle(), o.getUnits(), o.getLevel());
    }

    public static PersonRef person(UUID id, String name) {
        return name == null ? null : new PersonRef(id, name, Labels.initials(name));
    }

    public static long estimate(String quality, Integer seconds) {
        return seconds == null ? 0 : BYTES_PER_SECOND.getOrDefault(quality, 40_195L) * seconds;
    }

    // ---- staff

    public static String statusLabel(Item i) {
        return switch (i.getStatus()) {
            case UPLOADING -> "Uploading";
            case PROCESSING -> "Processing";
            case CONVERTING -> "Converting";
            case KEPT_ORIGINAL -> "Kept as the original file";
            case FAILED -> "Failed";
            case READY -> i.getKind() == Item.Kind.PAGE ? "Published" : i.isConverted() ? "Converted" : "Ready";
        };
    }

    static String ago(Instant t, Instant now) {
        if (t == null) return "just now";
        long min = Duration.between(t, now).toMinutes();
        if (min < 1) return "just now";
        if (min < 60) return min + (min == 1 ? " minute ago" : " minutes ago");
        long h = min / 60;
        return h < 24 ? h + (h == 1 ? " hour ago" : " hours ago") : Labels.shortDay(t.atZone(Labels.LAGOS).toLocalDate());
    }

    public static String staffMeta(Item i, Instant now) {
        String fmt = FileKinds.formatName(i.getFileName());
        return switch (i.getKind()) {
            case VIDEO -> switch (i.getStatus()) {
                case UPLOADING -> "Video · waiting for the upload to finish";
                case PROCESSING -> "Video · uploaded " + ago(i.getUploadedAt(), now) + " · making 240p/360p/480p + audio";
                case FAILED -> "Video · " + (i.getFailureMessage() == null ? "could not be prepared" : i.getFailureMessage());
                default -> "Video · " + Optional.ofNullable(Labels.length(i.getDurationSeconds())).orElse("length unknown") + " · "
                        + String.join("/", i.qualityMap().keySet()) + audioNote(i);
            };
            case AUDIO -> "Audio · " + sizeOr(i);
            case PDF -> "PDF · " + sizeOr(i) + (i.getPages() == null ? "" : " · " + i.getPages() + (i.getPages() == 1 ? " page" : " pages"));
            case SLIDES, NOTES -> switch (i.getStatus()) {
                case CONVERTING -> fmt + " · " + Labels.size(i.getOriginalSizeBytes()) + " · converting to PDF";
                case KEPT_ORIGINAL -> fmt + " · " + Labels.size(i.getOriginalSizeBytes()) + " · kept as the original file";
                case READY -> fmt + " → PDF · " + Labels.size(i.getOriginalSizeBytes()) + " → " + Labels.size(i.getSizeBytes());
                default -> fmt + (i.getOriginalSizeBytes() == null ? "" : " · " + Labels.size(i.getOriginalSizeBytes()));
            };
            case LINK -> "Link · " + host(i.getUrl()) + (i.isWarnData() ? " · students are warned it uses data" : "");
            case PAGE -> "Text page · " + readMinutes(i.getBody()) + " min read";
        };
    }

    private static String audioNote(Item i) {
        if (i.getAudioStatus() == null) return "";
        return switch (i.getAudioStatus()) {
            case READY -> " + audio ready";
            case PENDING -> " + audio being made";
            case SKIPPED, FAILED -> " + audio (from 240p)";
        };
    }

    private static String sizeOr(Item i) {
        return i.getSizeBytes() == null ? "size not known yet" : Labels.size(i.getSizeBytes());
    }

    public static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return url;
        }
    }

    public static int readMinutes(String body) {
        if (body == null || body.isBlank()) return 1;
        int words = body.trim().split("\\s+").length;
        return Math.max(1, (int) Math.ceil(words / 200.0));
    }

    public static StaffItem staff(Item i, long openedBy, Item replacement, Instant now, String message) {
        Converted conv = (i.getKind() == Item.Kind.SLIDES || i.getKind() == Item.Kind.NOTES) && i.isConverted()
                ? new Converted(i.getOriginalSizeBytes(), i.getSizeBytes(), FileKinds.formatName(i.getFileName()) + " → PDF · "
                + Labels.size(i.getOriginalSizeBytes()) + " → " + Labels.size(i.getSizeBytes())) : null;
        FileInfo file = i.getFileName() == null ? null : new FileInfo(i.getFileName(), i.getContentType(),
                i.getOriginalSizeBytes() != null ? i.getOriginalSizeBytes() : i.getSizeBytes());
        Failure failure = i.getStatus() == Item.Status.FAILED || i.getFailureCode() != null ? new Failure(i.getFailureCode(), i.getFailureMessage()) : null;
        Replacement rep = replacement == null ? null : new Replacement(replacement.getId(), replacement.getStatus().name(),
                replacement.getStatus() == Item.Status.FAILED ? "The new file failed: " + replacement.getFailureMessage() : "New file: " + statusLabel(replacement).toLowerCase(Locale.ROOT));
        return new StaffItem(i.getId(), i.getWeekId(), i.getPosition(), i.getKind().name(), i.getTitle(), i.isHidden(), i.getStatus().name(),
                statusLabel(i), staffMeta(i, now), i.getDurationSeconds(), i.getSizeBytes(), Labels.size(i.getSizeBytes()), i.getPages(), file, conv,
                i.getUrl(), i.getNote(), i.isWarnData(), i.getBody(), i.getKind() == Item.Kind.PAGE ? readMinutes(i.getBody()) : null, openedBy,
                failure, rep, i.getUploadedAt(), i.getUpdatedAt(), message);
    }

    public static String weekStatus(Week w, int items, LocalDate today) {
        String count = items == 0 ? "Empty" : items + (items == 1 ? " item" : " items");
        if (w.isHidden()) return count + " · hidden from students";
        if (w.getOpensOn() == null) return count + " · no open date";
        if (w.openOn(today)) return count + " · released";
        return count + " · opens " + Labels.day(w.getOpensOn());
    }

    public static String releaseLabel(Week w, LocalDate today) {
        if (w.isHidden()) return "Hidden from students";
        if (w.getOpensOn() == null) return "No open date";
        return (w.openOn(today) ? "Released " : "Opens ") + Labels.day(w.getOpensOn());
    }

    // ---- student

    public static String studentMeta(Item i) {
        return switch (i.getKind()) {
            case VIDEO -> "Video" + Optional.ofNullable(Labels.length(i.getDurationSeconds())).map(s -> " · " + s).orElse("");
            case AUDIO -> "Audio · " + sizeOr(i);
            case PDF, SLIDES, NOTES -> (i.getStatus() == Item.Status.KEPT_ORIGINAL ? FileKinds.formatName(i.getFileName()) : "PDF") + " · " + sizeOr(i)
                    + (i.getPages() == null ? "" : " · " + i.getPages() + (i.getPages() == 1 ? " page" : " pages"));
            case LINK -> "Link · " + (i.isWarnData() ? "opens your browser, uses data" : "opens your browser");
            case PAGE -> "Page · " + readMinutes(i.getBody()) + " min read";
        };
    }

    public static String dataCost(Item i) {
        return switch (i.getKind()) {
            case VIDEO -> {
                if (!i.usable()) yield null;
                Long q360 = i.qualityMap().getOrDefault("360p", estimate("360p", i.getDurationSeconds()));
                Long audio = audioSize(i);
                yield "about " + Labels.size(q360) + " at 360p" + (audio == null ? "" : " · " + Labels.size(audio) + " audio only");
            }
            case AUDIO, PDF, SLIDES, NOTES -> i.getSizeBytes() == null ? null : Labels.size(i.getSizeBytes());
            case LINK -> i.isWarnData() ? "opens your browser, uses data" : "opens your browser";
            case PAGE -> "under 10 KB";
        };
    }

    /** The audio-only size: the audio file, else the 240p stream played without the picture. */
    public static Long audioSize(Item i) {
        if (i.getAudioStatus() == Item.AudioStatus.READY && i.getAudioSizeBytes() != null) return i.getAudioSizeBytes();
        if (i.getAudioStatus() == Item.AudioStatus.PENDING && i.getDurationSeconds() != null) return AUDIO_BYTES_PER_SECOND * i.getDurationSeconds();
        if (i.getDurationSeconds() == null) return null;
        return i.qualityMap().getOrDefault("240p", estimate("240p", i.getDurationSeconds()));
    }

    public static StudentItem student(Item i, ItemProgress p) {
        boolean ready = i.usable();
        ItemProgressView pv = new ItemProgressView(p != null && p.isDone(), p == null ? 0 : p.getPositionSeconds(),
                p == null ? 0 : p.getPercentWatched(), p == null ? null : p.getOpenedAt());
        return new StudentItem(i.getId(), i.getKind().name(), i.getTitle(), ready, ready ? null : "Not ready", studentMeta(i), dataCost(i),
                i.getDurationSeconds(), i.getSizeBytes(), Labels.size(i.getSizeBytes()), i.getPages(),
                i.getKind() == Item.Kind.PAGE ? readMinutes(i.getBody()) : null, i.getKind() == Item.Kind.LINK ? i.getUrl() : null,
                i.getKind() == Item.Kind.LINK ? i.getNote() : null, i.isWarnData(), i.getKind() == Item.Kind.LINK ? host(i.getUrl()) : null, pv);
    }
}
