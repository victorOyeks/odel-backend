package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "imports")
@Getter
@Setter
public class ImportJob {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    public enum Kind { STRUCTURE, CATALOGUE }
    public enum Status { CHECKED, IMPORTED, CANCELLED }
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String fileName;
    private String fileKey;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int totalRows;
    private int readyRows;
    private int problemRows;
    private String problems;
    private String problemCsv;
    private String payload;
    private String imported;
    private Instant createdAt;
    private UUID createdBy;
    private Instant confirmedAt;
}
