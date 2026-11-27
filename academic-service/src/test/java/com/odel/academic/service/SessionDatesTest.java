package com.odel.academic.service;

import com.odel.academic.dto.SessionDtos.Registration;
import com.odel.academic.dto.SessionDtos.SemesterIn;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionDatesTest {
    static LocalDate d(String s) { return LocalDate.parse(s); }

    SemesterIn first = new SemesterIn(1, d("2026-10-05"), d("2027-02-26"), new Registration(d("2027-02-02"), d("2027-02-16"), null));
    SemesterIn second = new SemesterIn(2, d("2027-03-15"), d("2027-07-30"), null);

    @Test
    void storySessionIsFine() {
        assertThat(SessionDates.check("2026/2027", List.of(first, second), true)).isEmpty();
        assertThat(SessionDates.range(d("2027-02-02"), d("2027-02-16"), false)).isEqualTo("2–16 Feb");
        assertThat(SessionDates.range(d("2027-01-28"), d("2027-02-03"), true)).isEqualTo("28 Jan – 3 Feb 2027");
    }

    @Test
    void nameShape() {
        assertThat(SessionDates.check("2026-27", List.of(first), false)).containsKey("name");
        assertThat(SessionDates.check("2026/2028", List.of(first), false)).containsEntry("name", "The second year must follow the first, as in 2026/2027.");
    }

    @Test
    void datesInOrder() {
        SemesterIn backwards = new SemesterIn(1, d("2027-02-26"), d("2026-10-05"), null);
        assertThat(SessionDates.check("2026/2027", List.of(backwards), false)).containsEntry("semesters[0].endsOn", "The first semester must end after it starts.");
        SemesterIn early = new SemesterIn(2, d("2027-02-01"), d("2027-07-30"), null);
        assertThat(SessionDates.check("2026/2027", List.of(first, early), false).get("semesters[1].startsOn"))
                .startsWith("The second semester must start after the first one ends");
    }

    @Test
    void registrationWindow() {
        SemesterIn closesBeforeOpens = new SemesterIn(1, d("2026-10-05"), d("2027-02-26"), new Registration(d("2027-02-16"), d("2027-02-02"), null));
        assertThat(SessionDates.check("2026/2027", List.of(closesBeforeOpens), false)).containsKey("semesters[0].registration.closesOn");
        SemesterIn late = new SemesterIn(1, d("2026-10-05"), d("2027-02-26"), new Registration(d("2027-02-02"), d("2027-02-16"), d("2027-02-10")));
        assertThat(SessionDates.check("2026/2027", List.of(late), false)).containsKey("semesters[0].registration.lateClosesOn");
        SemesterIn afterEnd = new SemesterIn(1, d("2026-10-05"), d("2027-02-26"), new Registration(d("2027-02-20"), d("2027-03-02"), null));
        assertThat(SessionDates.check("2026/2027", List.of(afterEnd), false)).containsKey("semesters[0].registration.closesOn");
    }

    @Test
    void activeNeedsBothSemestersAndAWindow() {
        assertThat(SessionDates.check("2026/2027", List.of(first), false)).isEmpty();
        assertThat(SessionDates.check("2026/2027", List.of(first), true)).containsKey("semesters");
        SemesterIn noWindow = new SemesterIn(1, d("2026-10-05"), d("2027-02-26"), null);
        assertThat(SessionDates.check("2026/2027", List.of(noWindow, second), true))
                .containsEntry("semesters", "Set the registration window before making the session active.");
    }

    @Test
    void overlap() {
        assertThat(SessionDates.overlap(d("2026-10-05"), d("2027-07-30"), d("2025-10-06"), d("2026-10-05"))).isTrue();
        assertThat(SessionDates.overlap(d("2026-10-05"), d("2027-07-30"), d("2025-10-06"), d("2026-10-04"))).isFalse();
    }
}
