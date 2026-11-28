package com.odel.registration.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Late registration and course change requests (R-06 … R-09e) and the approvals queue (A-03 … A-03e). */
public final class RequestDtos {
    private RequestDtos() {}

    public record UploadRequest(String fileName, String contentType, Long size) {}

    public record UploadLink(String key, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {}

    public record Attachment(String key, String fileName, String contentType, Long size) {}

    public record LateRequestIn(UUID semesterId, String reasonCategory, String reason, Attachment attachment) {}

    public record ChangeRequestIn(UUID registrationId, List<UUID> dropCourseIds, List<UUID> addCourseIds, String reason) {}

    public record DecisionIn(String note) {}

    public record StudentBrief(UUID id, String matricNumber, String fullName, String programme, Integer level, String levelLabel) {}

    public record CentreBrief(UUID id, String name, boolean hasCoordinator) {}

    public record AttachmentView(String fileName, Long size, String sizeLabel, String contentType, String type) {}

    public record CourseRef(UUID courseId, String code, String title, int units) {}

    public record ChangeView(List<CourseRef> drop, List<CourseRef> add, Integer unitsBefore, Integer unitsAfter, Integer maxUnits, String summary) {}

    public record DecisionView(Instant at, String by, String role, String note) {}

    public record RequestView(UUID id, String kind, String status, String statusLabel, RegistrationDtos.SemesterView semester, StudentBrief student,
                              CentreBrief centre, String reasonCategory, String reasonCategoryLabel, String reason, AttachmentView attachment,
                              ChangeView change, LocalDate registerBy, DecisionView decision, Instant createdAt, LocalDate registeredOn) {}

    public record RequestDetail(RequestView request, RegistrationDtos.RegistrationView registration) {}

    public record Requests(List<RequestView> items) {}

    public record Counts(long late, long change) {}

    public record Queue(List<RequestView> items, int page, int size, long total, Counts counts) {}

    public record Link(String url, Instant expiresAt) {}
}
