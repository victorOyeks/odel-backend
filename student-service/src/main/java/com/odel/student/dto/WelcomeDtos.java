package com.odel.student.dto;

import com.odel.student.service.WelcomeTemplates;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Welcome emails (W-20, W-20c, W-20d). */
public final class WelcomeDtos {
    private WelcomeDtos() {}

    public record TemplateView(String subject, String body, boolean custom, Instant updatedAt, String updatedBy) {}

    public record Preview(String studentName, String subject, String body) {}

    public record Audience(long notActivated, long withEmail, long noEmail, long alreadyActive) {}

    public record Overview(TemplateView template, List<WelcomeTemplates.Field> fields, Preview preview, Audience audience, SendView lastSend) {}

    public record TemplateRequest(String subject, String body) {}

    public record TestRequest(String subject, String body) {}

    public record TestResult(String sentTo) {}

    public record StartRequest(Integer remindAfterDays) {}

    public record Totals(long students, long toSend, long sent, long failed, long sending, long skippedNoEmail, long skippedTestAddress,
                         long skippedActive) {}

    public record SendView(UUID id, String kind, String status, Instant startedAt, Instant finishedAt, Integer remindAfterDays,
                           Instant reminderDueAt, Totals totals, int progressPercent, String requestedBy) {}

    public record DeliveryView(UUID studentId, String matricNumber, String fullName, String email, String status, String reason, Instant updatedAt) {}
}
