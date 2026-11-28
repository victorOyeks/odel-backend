package com.odel.student.dto;

import java.util.List;
import java.util.UUID;

/** GET /internal/students/directory: who the students are, for other services (payments B2: fee list, receipts, emails). */
public final class DirectoryDtos {
    private DirectoryDtos() {}

    public record Programme(String code, String name) {}

    public record Centre(UUID id, String code, String name, String shortName) {}

    public record Entry(UUID id, UUID userId, String matricNumber, String fullName, String firstName, String email, Programme programme,
                        int level, String levelLabel, Centre centre, String status) {}

    public record Directory(List<Entry> items) {}
}
