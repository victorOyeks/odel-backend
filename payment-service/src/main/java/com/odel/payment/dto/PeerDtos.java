package com.odel.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What payment-service reads from the other services' /internal endpoints. */
public final class PeerDtos {
    private PeerDtos() {}

    // academic-service GET /internal/academic/semesters
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Semester(UUID id, UUID sessionId, String sessionName, String sessionStatus, int number, String name, String label,
                           LocalDate startsOn, LocalDate endsOn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Semesters(UUID currentSessionId, UUID currentSemesterId, List<Semester> items) {}

    // student-service GET /internal/students/directory
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Programme(String code, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Centre(UUID id, String code, String name, String shortName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Student(UUID id, UUID userId, String matricNumber, String fullName, String firstName, String email, Programme programme,
                          int level, String levelLabel, Centre centre, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Directory(List<Student> items) {}

    // tenant-service GET /internal/tenants/{id}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TenantView(UUID id, String kind, String slug, String name, String shortName, String status, String primaryColor,
                             String portalUrl, String logoUrl) {}

    // identity-service GET /internal/users/{id}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StaffView(UUID id, String fullName, String email) {}

    // notification-service POST /internal/messages
    public record TenantLook(String name, String shortName, String primaryColor) {}

    public record Attachment(String fileName, String contentType, String base64) {}

    public record Message(UUID userId, String email, String templateKey, Map<String, ?> vars, String link, TenantLook tenant,
                          List<Attachment> attachments) {}
}
