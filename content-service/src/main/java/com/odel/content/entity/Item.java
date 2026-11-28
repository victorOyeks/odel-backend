package com.odel.content.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One entry in a week: a video (Bunny Stream), an audio file, a PDF, slides or notes (storage), a link or a text page. */
@Entity
@Table(name = "items")
@Getter
@Setter
public class Item {
    public enum Kind { VIDEO, AUDIO, PDF, SLIDES, NOTES, LINK, PAGE }
    public enum Status { UPLOADING, PROCESSING, CONVERTING, READY, KEPT_ORIGINAL, FAILED }
    public enum AudioStatus { PENDING, READY, SKIPPED, FAILED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID offeringId;
    private UUID weekId;
    private int position;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String title;
    @Enumerated(EnumType.STRING)
    private Status status;
    private boolean hidden;
    private String storageKey;
    private String originalKey;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private Long originalSizeBytes;
    private Integer pages;
    private boolean pagesChecked;
    private boolean converted;
    private String videoId;
    private Integer durationSeconds;
    private String qualities;
    private Integer encodeProgress;
    @Enumerated(EnumType.STRING)
    private AudioStatus audioStatus;
    private String audioKey;
    private Long audioSizeBytes;
    private String url;
    private String note;
    private boolean warnData;
    private String body;
    private String failureCode;
    private String failureMessage;
    private UUID replacesItemId;
    private UUID createdBy;
    private String createdByName;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant uploadedAt;
    private Instant readyAt;
    private Instant lastCheckedAt;

    /** Ready for students (a file kept as uploaded counts). */
    public boolean usable() {
        return status == Status.READY || status == Status.KEPT_ORIGINAL;
    }

    public boolean isFile() {
        return kind == Kind.AUDIO || kind == Kind.PDF || kind == Kind.SLIDES || kind == Kind.NOTES;
    }

    /** Sizes per quality ("240p" → bytes), in quality order. */
    public java.util.LinkedHashMap<String, Long> qualityMap() {
        java.util.LinkedHashMap<String, Long> m = new java.util.LinkedHashMap<>();
        if (qualities == null || qualities.isBlank()) return m;
        for (String part : qualities.split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2) m.put(kv[0], Long.parseLong(kv[1]));
        }
        return m;
    }

    public void qualityMap(java.util.Map<String, Long> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ",").append(k).append(':').append(v));
        qualities = sb.isEmpty() ? null : sb.toString();
    }
}
