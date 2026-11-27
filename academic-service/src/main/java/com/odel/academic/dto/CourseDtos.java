package com.odel.academic.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: Course catalogue (W-13 …). */
public final class CourseDtos {
    private CourseDtos() {}

    public record Offer(UUID programmeId, String programmeName, String kind) {}

    public record CourseView(UUID id, String code, String title, int units, int level, int semester, String semesterLabel,
                             List<Offer> offeredTo, StaffingDtos.Person lecturer, boolean needsLecturer) {}

    public record CoursePage(List<CourseView> items, int page, int size, long total, long catalogueTotal, long programmeTotal) {}

    public record OfferIn(UUID programmeId, String kind) {}

    public record CourseIn(String code, String title, Integer units, Integer level, Integer semester, List<OfferIn> offeredTo, UUID lecturerId) {}
}
