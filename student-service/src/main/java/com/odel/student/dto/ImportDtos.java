package com.odel.student.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Import (W-18, W-18b, W-19). */
public final class ImportDtos {
    private ImportDtos() {}

    public record UploadRequest(String fileName, String contentType, Long size) {}

    public record UploadLink(String key, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {}

    public record ValidateRequest(String key, String fileName) {}

    public record Ready(int count, @com.fasterxml.jackson.annotation.JsonProperty("new") int added, int updated, int unchanged) {}

    public record Count(int count) {}

    public record ProblemView(int row, String matricNumber, String name, String severity, String field, String message) {}

    public record Person(UUID id, String fullName) {}

    public record Report(UUID importId, String fileName, String status, int totalRows, Ready ready, Count errors, Count warnings,
                         List<ProblemView> problems, int problemsShown, Instant checkedAt, Instant importedAt, Person importedBy) {}

    public record ConfirmResult(UUID importId, String status, int added, int updated, int unchanged, int skipped, int warnings,
                                Instant importedAt, Person importedBy) {}

    public record HistoryItem(UUID id, String fileName, Instant importedAt, Person importedBy, int totalRows, int added, int updated,
                              int unchanged, int errors, int warnings) {}
}
