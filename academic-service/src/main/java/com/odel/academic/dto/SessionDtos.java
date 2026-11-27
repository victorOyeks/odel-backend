package com.odel.academic.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: Sessions and semesters (W-11 …). */
public final class SessionDtos {
    private SessionDtos() {}

    public record RegistrationView(LocalDate opensOn, LocalDate closesOn, LocalDate lateClosesOn) {}

    public record SemesterView(UUID id, int number, String name, LocalDate startsOn, LocalDate endsOn, RegistrationView registration) {}

    public record SessionView(UUID id, String name, String status, LocalDate startsOn, LocalDate endsOn, boolean current,
                              List<SemesterView> semesters, String semestersLabel, String registrationLabel, boolean complete) {}

    public record SessionIn(String name, List<SemesterIn> semesters, Boolean makeActive) {}

    public record Registration(LocalDate opensOn, LocalDate closesOn, LocalDate lateClosesOn) {}

    public record SemesterIn(Integer number, LocalDate startsOn, LocalDate endsOn, Registration registration) {}
}
