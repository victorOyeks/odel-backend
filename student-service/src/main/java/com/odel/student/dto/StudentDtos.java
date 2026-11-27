package com.odel.student.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Students list and record (W-22, W-22b, W-22c), the student's own record (M-06) and the internal views. */
public final class StudentDtos {
    private StudentDtos() {}

    public record ProgrammeView(String code, String name) {}

    public record CentreView(String code, String name, String shortName) {}

    public record ListItem(UUID id, String matricNumber, String fullName, ProgrammeView programme, int level, String levelLabel,
                           CentreView centre, String email, String status, String statusLabel) {}

    public record Consent(String version, Instant at) {}

    public record Account(Instant welcomeSentAt, Instant activatedAt, Consent consent, Instant lastSignInAt, Instant lockedUntil) {}

    public record Person(String fullName) {}

    public record HistoryItem(Instant at, String from, String to, String reason, Person by) {}

    public record ImportRef(UUID importId, String fileName) {}

    public record Record(UUID id, String matricNumber, String surname, String firstName, String otherNames, String fullName, String email,
                         String phone, String gender, LocalDate dateOfBirth, ProgrammeView programme, int level, String levelLabel,
                         CentreView centre, String entrySession, String status, String statusLabel, Account account,
                         List<HistoryItem> history, Instant createdAt, ImportRef importedFrom) {}

    public record AddRequest(String surname, String firstName, String otherNames, String matricNumber, String programmeCode, Integer level,
                             String centreCode, String email, String phone, Boolean sendWelcome) {}

    public record UpdateRequest(String surname, String firstName, String otherNames, String matricNumber, String email, String phone,
                                String programmeCode, Integer level, String centreCode) {}

    public record SuspendRequest(String reason) {}

    public record FilterProgramme(String code, String name, List<Integer> levels) {}

    public record FilterCentre(String code, String name, String shortName) {}

    public record FilterStatus(String key, String label) {}

    public record Filters(List<FilterProgramme> programmes, List<FilterCentre> centres, List<FilterStatus> statuses) {}

    public record Profile(UUID id, String matricNumber, String fullName, String firstName, String initials, ProgrammeView programme, int level,
                          String levelLabel, CentreView centre, String email, String phone, String status, Consent consent) {}

    public record ProfileUpdate(String phone) {}

    // ---- internal ----

    /** For academic-service's setup checklist: step 8 done when total > 0, step 9 when welcome.notSent = 0. */
    public record Summary(long total, long active, Instant lastImportAt, WelcomeCounts welcome, List<CentreCount> byCentre) {}

    /** sent: handed to email; failed: not active and no email, or the email failed; notSent: not active, has an email, never sent. */
    public record WelcomeCounts(long sent, long failed, long notSent) {}

    public record CentreCount(UUID centreId, long students) {}

    public record ContactCentre(String name, String phone) {}

    public record Contact(UUID studentId, String firstName, ContactCentre centre) {}

    public record Activated(UUID userId, Instant activatedAt, String consentVersion, Instant consentAt) {}
}
