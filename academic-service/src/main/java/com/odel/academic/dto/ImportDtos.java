package com.odel.academic.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: structure and catalogue CSV imports (W-12c, W-13c). */
public final class ImportDtos {
    private ImportDtos() {}

    public record UploadRequest(String fileName, String contentType, Long size) {}

    public record UploadResponse(String key, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {}

    public record CheckRequest(String key, String fileName) {}

    public record Problem(int row, String message) {}

    public record ImportView(UUID id, String kind, String fileName, String status, int totalRows, int readyRows, int problemRows,
                             List<Problem> problems, String summary, Map<String, Integer> imported, Instant createdAt, Instant confirmedAt) {}

    /** One data row as read from the file, kept between the check and the confirm. */
    public record StoredRow(int row, List<String> cells) {}
}
