package com.odel.content.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Week 1..n of an offering; open to students from opensOn (none = not dated yet) unless hidden. */
@Entity
@Table(name = "weeks")
@Getter
@Setter
public class Week {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID offeringId;
    private int number;
    private String title;
    private LocalDate opensOn;
    private String note;
    private boolean hidden;
    private Instant createdAt;
    private Instant updatedAt;

    public boolean openOn(LocalDate today) {
        return !hidden && opensOn != null && !opensOn.isAfter(today);
    }
}
