package com.odel.academic.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: Study centres and rooms (W-14 …). */
public final class CentreDtos {
    private CentreDtos() {}

    public record RoomView(UUID id, String name, int seats, int computers) {}

    public record Coordinator(UUID id, String fullName) {}

    public record CentreView(UUID id, String name, String code, String address, String state, String phone, Coordinator coordinator,
                             int rooms, int seats, int computers, long students, List<RoomView> roomsList) {}

    public record CentreIn(String name, String code, String address, String state, String phone) {}

    public record RoomIn(String name, Integer seats, Integer computers) {}
}
