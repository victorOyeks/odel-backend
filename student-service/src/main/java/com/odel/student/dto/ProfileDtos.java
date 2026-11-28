package com.odel.student.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** B3 My profile (L-14 … L-14j) and the ODeL office's review of corrections and photos. */
public final class ProfileDtos {
    private ProfileDtos() {}

    public record Address(String line, String town, String state) {}

    public record NextOfKin(String name, String relationship, String phone) {}

    public record Photo(String url, Instant updatedAt) {}

    public record PendingPhoto(UUID requestId, String url, Instant submittedAt) {}

    public record ReadOnly(String programme, String centre) {}

    /** GET /student-profile: the B1 fields (M-06) plus the B3 ones. */
    public record MyProfile(UUID id, String matricNumber, String fullName, String firstName, String initials, String surname, String otherNames,
                            LocalDate dateOfBirth, String gender, StudentDtos.ProgrammeView programme, int level, String levelLabel,
                            StudentDtos.CentreView centre, String email, String maskedEmail, String phone, String maskedPhone, Address address,
                            NextOfKin nextOfKin, Photo photo, PendingPhoto pendingPhoto, List<RequestView> openRequests, String status,
                            StudentDtos.Consent consent, ReadOnly readOnly) {}

    public record UploadRequest(String purpose, String fileName, String contentType, Long size) {}

    public record UploadLink(String key, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {}

    public record PhotoRequest(String key) {}

    public record Proof(String key, String fileName, String contentType, Long size) {}

    public record CorrectionRequest(String field, String requestedValue, String note, Proof proof) {}

    public record CorrectionField(String key, String label, String current) {}

    public record FileView(String fileName, Long size, String sizeLabel, String contentType, String type) {}

    public record Decision(Instant at, String by, String note) {}

    public record StudentBrief(UUID id, String matricNumber, String fullName, StudentDtos.CentreView centre) {}

    public record RequestView(UUID id, String kind, String field, String fieldLabel, String currentValue, String requestedValue, String note,
                              FileView proof, String status, String statusLabel, Instant submittedAt, Decision decision) {}

    public record Requests(List<RequestView> items) {}

    public record ReviewItem(UUID id, String kind, String field, String fieldLabel, String currentValue, String requestedValue, String status,
                             String statusLabel, Instant submittedAt, StudentBrief student, boolean hasProof) {}

    public record Counts(long pending, long corrections, long photos) {}

    public record ReviewPage(List<ReviewItem> items, int page, int size, long total, Counts counts) {}

    public record ReviewDetail(RequestView request, StudentBrief student, String currentPhotoUrl, String newPhotoUrl) {}

    public record DecisionRequest(String note) {}

    public record Link(String url, Instant expiresAt) {}

    public record ChangeView(Instant at, String field, String fieldLabel, String oldValue, String newValue, String source, String sourceLabel,
                             StudentDtos.Person by, UUID requestId) {}

    public record EmailChanged(String email, String oldEmail) {}
}
