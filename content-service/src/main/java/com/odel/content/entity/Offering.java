package com.odel.content.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One course in one semester: its weeks, items, announcements and progress hang off it. */
@Entity
@Table(name = "offerings")
@Getter
@Setter
public class Offering {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID courseId;
    private String courseCode;
    private String courseTitle;
    private int units;
    private int level;
    private UUID semesterId;
    private int semesterNumber;
    private UUID sessionId;
    private String sessionName;
    private String outline;
    private String objectives;
    private UUID copiedFromId;
    private Instant createdAt;
    private Instant updatedAt;

    public java.util.List<String> objectiveList() {
        return objectives == null || objectives.isBlank() ? java.util.List.of() : java.util.Arrays.stream(objectives.split("\n")).filter(s -> !s.isBlank()).toList();
    }

    public String semesterLabel() {
        return sessionName + " · " + (semesterNumber == 1 ? "1st semester" : semesterNumber == 2 ? "2nd semester" : semesterNumber + "th semester");
    }
}
