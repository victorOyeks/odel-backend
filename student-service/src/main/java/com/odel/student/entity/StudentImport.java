package com.odel.student.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/** One portal file: checked (W-19, nothing saved yet) and then imported (W-18b history). */
@Entity
@Table(name = "imports")
@Getter
@Setter
public class StudentImport {
    public enum Status { CHECKED, IMPORTED }

    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private String fileKey;
    private String fileName;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int totalRows;
    private int readyCount;
    private int newCount;
    private int updatedCount;
    private int unchangedCount;
    private int errorCount;
    private int warningCount;
    private Instant checkedAt;
    private UUID checkedBy;
    private String checkedByName;
    private Instant importedAt;
    private UUID importedBy;
    private String importedByName;
}
