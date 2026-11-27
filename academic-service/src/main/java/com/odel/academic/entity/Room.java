package com.odel.academic.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter
@Setter
public class Room {
    @Id
    private UUID id;
    @TenantId
    private UUID tenantId;
    private UUID centreId;
    private String name;
    private int seats;
    private int computers;
    private Instant createdAt;
}
