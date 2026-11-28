package com.odel.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses of content-service (docs/api-b3-content.md). */
public final class ContentDtos {
    private ContentDtos() {}

    // ---- shared pieces
    public record SemesterRef(UUID id, UUID sessionId, String sessionName, int number, String label) {}

    public record SemesterOption(UUID id, String label) {}

    public record CourseRef(UUID id, String code, String title, int units, int level) {}

    public record PersonRef(UUID id, String fullName, String initials) {}

    public record Progress(int percent, int weeksDone, int weeksTotal, int itemsDone, int itemsTotal, String label) {}

    // ---- staff: courses and overview (T-01, T-02, T-08)
    public record CourseCard(UUID offeringId, CourseRef course, PersonRef lecturer, String myRole, boolean canEdit, int students, int weeks,
                             int weeksReady, String readyLabel, boolean empty) {}

    public record CourseList(SemesterRef semester, List<SemesterOption> semesters, List<CourseCard> items, String subtitle) {}

    public record CopiedFrom(UUID offeringId, String label) {}

    public record Overview(UUID id, CourseRef course, SemesterRef semester, PersonRef lecturer, boolean canEdit, String myRole, String outline,
                           List<String> objectives, String assessment, int weeks, int weeksReady, int items, int students, boolean empty,
                           int contentComplete, String contentLabel, CopiedFrom copiedFrom, boolean canCopy) {}

    public record SettingsIn(String outline, List<String> objectives) {}

    // ---- weeks
    public record WeekRow(UUID id, int number, String title, LocalDate opensOn, boolean open, boolean hidden, int itemCount, int readyCount,
                          String statusLabel, String note) {}

    public record WeekList(List<WeekRow> items) {}

    public record WeekIn(String title, LocalDate opensOn, String note, Boolean hidden) {}

    public record WeekView(UUID id, UUID offeringId, int number, String title, LocalDate opensOn, boolean open, boolean hidden, String note,
                           String releaseLabel, List<StaffItem> items, String message) {}

    public record DatesIn(LocalDate firstOpensOn, Integer everyDays, Boolean onlyEmpty) {}

    public record DatesResult(int updated, String message) {}

    public record OrderIn(List<UUID> itemIds) {}

    // ---- items
    public record FileInfo(String name, String contentType, Long sizeBytes) {}

    public record Converted(Long fromBytes, Long toBytes, String label) {}

    public record Failure(String code, String message) {}

    public record Replacement(UUID id, String status, String statusLabel) {}

    public record StaffItem(UUID id, UUID weekId, int position, String kind, String title, boolean hidden, String status, String statusLabel,
                            String meta, Integer durationSeconds, Long sizeBytes, String sizeLabel, Integer pages, FileInfo file,
                            Converted converted, String url, String note, boolean warnData, String body, Integer readMinutes, long openedBy,
                            Failure failure, Replacement replacement, Instant uploadedAt, Instant updatedAt, String message) {}

    public record ItemIn(String kind, String title, String url, String note, Boolean warnData, String body) {}

    public record UploadIn(String fileName, String contentType, Long sizeBytes, String title) {}

    public record Upload(String protocol, String url, String method, String endpoint, Map<String, String> headers, Map<String, String> metadata,
                         Instant expiresAt) {}

    public record UploadStarted(StaffItem item, Upload upload) {}

    public record MoveIn(UUID weekId) {}

    // ---- copy (T-06)
    public record CopySource(UUID offeringId, String label, int weeks, int items, String lecturer) {}

    public record CopySources(List<CopySource> items) {}

    public record CopyIn(UUID fromOfferingId) {}

    public record Skipped(String title, String reason) {}

    public record CopyResult(int weeks, int items, List<Skipped> skipped, String message) {}

    // ---- student
    public record StudentCourseCard(UUID offeringId, CourseRef course, PersonRef lecturer, Progress progress, WeekRef currentWeek, boolean empty) {}

    public record WeekRef(UUID id, int number, String title) {}

    public record StudentCourses(SemesterRef semester, boolean registered, List<StudentCourseCard> items, String subtitle, String message,
                                 String registerPath) {}

    public record About(String outline, List<String> objectives, String assessment) {}

    public record StudentWeekRow(UUID id, int number, String title, boolean open, LocalDate opensOn, String openLabel, int itemsDone, int itemsTotal,
                                 boolean done, boolean current) {}

    public record Continue(UUID itemId, UUID offeringId, String course, String title, String kind, int positionSeconds, Integer durationSeconds,
                           Integer weekNumber, String label) {}

    public record StudentOffering(UUID id, CourseRef course, SemesterRef semester, PersonRef lecturer, About about, Progress progress,
                                  List<StudentWeekRow> weeks, List<AnnouncementView> announcements, @JsonProperty("continue") Continue continueItem, boolean empty,
                                  String emptyMessage) {}

    public record ItemProgressView(boolean done, int positionSeconds, int percentWatched, Instant openedAt) {}

    public record StudentItem(UUID id, String kind, String title, boolean ready, String statusLabel, String meta, String dataCost,
                              Integer durationSeconds, Long sizeBytes, String sizeLabel, Integer pages, Integer readMinutes, String url,
                              String note, boolean warnData, String linkHost, ItemProgressView progress) {}

    public record StudentWeek(UUID id, int number, String title, String note, PersonRef lecturer, LocalDate opensOn, int itemsDone, int itemsTotal,
                              String progressLabel, List<StudentItem> items, boolean empty, String emptyMessage) {}

    public record FileLink(String url, String contentType, Long sizeBytes, String sizeLabel, String name, boolean converted) {}

    public record NotReady(String code, String message) {}

    public record NextRef(UUID id, String title, String kind) {}

    public record StudentItemDetail(StudentItem item, WeekRef week, NextRef next, FileLink file, String body, String warning, Playback playback,
                                    NotReady notReady) {}

    public record Quality(String quality, @JsonProperty("default") boolean isDefault, Long sizeBytes, String sizeLabel, String perMinuteLabel, String hlsUrl, String mp4Url) {}

    public record AudioOption(String kind, String url, Long sizeBytes, String sizeLabel, String perMinuteLabel, String saving) {}

    public record Auto(String hlsUrl) {}

    public record Playback(UUID itemId, String videoId, int durationSeconds, String defaultQuality, int resumeAt, Instant expiresAt, Auto auto,
                           List<Quality> qualities, AudioOption audio, List<Double> speeds, String embedUrl) {}

    public record ProgressIn(Integer positionSeconds, Integer fromSeconds, Integer toSeconds, String mode, Double speed) {}

    public record ProgressResult(boolean done, int percentWatched, int watchedSeconds) {}

    public record Done(boolean done) {}

    // ---- staff progress (T-07, T-07b)
    public record CentreRef(UUID id, String code, String name) {}

    public record Counts(int all, int inactive, int behind, int notStarted) {}

    public record ProgressRow(UUID studentId, String fullName, String matricNumber, CentreRef centre, int percent, int weeksDone, int videosWatched,
                              String videosLabel, int minutesWatched, Instant lastActiveAt, String lastActiveLabel, String status, String statusLabel) {}

    public record ProgressTable(Counts counts, int videos, int openWeeks, List<ProgressRow> items) {}

    public record WeekDone(int number, String title, int itemsDone, int itemsTotal, boolean done) {}

    public record VideoWatch(UUID itemId, String title, int minutesWatched, int percentWatched, boolean done) {}

    public record ProgressDetail(ProgressRow student, List<WeekDone> weeks, List<VideoWatch> videos) {}

    // ---- announcements
    public record AnnouncementView(UUID id, String title, String body, PersonRef author, Instant postedAt, String postedLabel, boolean edited,
                                   Instant editedAt, String audience, String audienceLabel, int recipients, boolean email, String message) {}

    public record AnnouncementList(List<AnnouncementView> items) {}

    public record AnnouncementIn(String title, String body, String audience, List<UUID> studentIds, Boolean email) {}

    // ---- internal
    public record CourseSummary(UUID courseId, UUID offeringId, int percent, int weeksDone, int weeksTotal) {}

    public record StudentSummary(List<CourseSummary> items) {}
}
