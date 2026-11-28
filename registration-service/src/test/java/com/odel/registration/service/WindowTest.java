package com.odel.registration.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WindowTest {
    static final LocalDate OPENS = LocalDate.of(2027, 2, 2), CLOSES = LocalDate.of(2027, 2, 16), LATE = LocalDate.of(2027, 2, 26);

    Window on(int month, int day) {
        return Window.of(LocalDate.of(2027, month, day), OPENS, CLOSES, LATE, null);
    }

    @Test
    void statesThroughTheSemester() {
        assertThat(on(2, 1).state()).isEqualTo(Window.State.NOT_OPEN);
        assertThat(on(2, 1).label()).isEqualTo("Opens Tue 2 Feb 2027");
        assertThat(on(2, 2).state()).as("open on the opening day").isEqualTo(Window.State.OPEN);
        assertThat(on(2, 16).state()).as("open on the closing day").isEqualTo(Window.State.OPEN);
        assertThat(on(2, 16).label()).isEqualTo("Open until Tue 16 Feb 2027");
        assertThat(on(2, 17).state()).isEqualTo(Window.State.LATE);
        assertThat(on(2, 26).state()).as("late until the late close, inclusive").isEqualTo(Window.State.LATE);
        assertThat(on(2, 26).label()).isEqualTo("Late registration until Fri 26 Feb 2027");
        assertThat(on(2, 27).state()).isEqualTo(Window.State.CLOSED);
    }

    @Test
    void noLateCloseMeansClosedAfterTheClose() {
        Window w = Window.of(LocalDate.of(2027, 2, 17), OPENS, CLOSES, null, null);
        assertThat(w.state()).isEqualTo(Window.State.CLOSED);
        assertThat(w.changesCloseOn()).isEqualTo(LocalDate.of(2027, 2, 17));
        assertThat(Window.of(LocalDate.of(2027, 2, 17), OPENS, CLOSES, CLOSES, null).state()).as("a late close equal to the close adds nothing")
                .isEqualTo(Window.State.CLOSED);
    }

    @Test
    void notSetWithoutDates() {
        assertThat(Window.of(LocalDate.of(2027, 2, 3), null, null, null, null).state()).isEqualTo(Window.State.NOT_SET);
        assertThat(Window.of(LocalDate.of(2027, 2, 3), OPENS, null, null, null).state()).isEqualTo(Window.State.NOT_SET);
    }

    @Test
    void changesCloseTheDayAfterTheLateCloseUnlessSet() {
        assertThat(on(2, 3).changesCloseOn()).isEqualTo(LocalDate.of(2027, 2, 27));
        assertThat(on(2, 27).changesOpen(LocalDate.of(2027, 2, 27))).isTrue();
        assertThat(on(2, 28).changesOpen(LocalDate.of(2027, 2, 28))).isFalse();
        Window custom = Window.of(LocalDate.of(2027, 3, 1), OPENS, CLOSES, LATE, LocalDate.of(2027, 3, 5));
        assertThat(custom.changesOpen(LocalDate.of(2027, 3, 1))).isTrue();
    }
}
