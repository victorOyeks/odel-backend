package com.odel.registration.service;

import com.odel.registration.dto.RegistrationDtos.Problem;
import com.odel.registration.service.CourseRules.Course;
import com.odel.registration.service.CourseRules.Kind;
import com.odel.registration.service.CourseRules.Limits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Adeola's 200L first semester: 4 compulsory (10 units) + CSC 103 carry-over (3) = 13 locked; electives CSC 209 (2), MTH 211 (3), CSC 211 (3). */
class CourseRulesTest {
    static Course c(String code, int units, Kind kind) {
        return new Course(UUID.nameUUIDFromBytes(code.getBytes()), code, code + " title", units, kind);
    }

    static final Course CSC201 = c("CSC 201", 3, Kind.COMPULSORY), CSC203 = c("CSC 203", 3, Kind.COMPULSORY), CSC205 = c("CSC 205", 2, Kind.COMPULSORY),
            GST201 = c("GST 201", 2, Kind.COMPULSORY), CSC209 = c("CSC 209", 2, Kind.ELECTIVE), MTH211 = c("MTH 211", 3, Kind.ELECTIVE),
            CSC211 = c("CSC 211", 3, Kind.ELECTIVE), CSC103 = c("CSC 103", 3, Kind.CARRY_OVER);
    static final List<Course> OFFERED = List.of(CSC201, CSC203, CSC205, GST201, CSC209, MTH211, CSC211);
    static final List<Course> CARRY = List.of(CSC103);
    static final Limits WESLEY = new Limits(15, 20, 3);

    static List<String> codes(List<Problem> p) {
        return p.stream().map(Problem::code).toList();
    }

    @Test
    void sevenCoursesEighteenUnits() {
        var r = CourseRules.check(OFFERED, CARRY, List.of(CSC209.id(), MTH211.id()), WESLEY);
        assertThat(r.ok()).isTrue();
        assertThat(r.totalUnits()).isEqualTo(18);
        assertThat(r.courses()).hasSize(7);
        assertThat(r.electiveCount()).isEqualTo(2);
        assertThat(r.courses().get(0).kind()).as("compulsory first").isEqualTo(Kind.COMPULSORY);
        assertThat(r.courses().stream().filter(x -> x.kind() == Kind.CARRY_OVER).map(Course::code)).containsExactly("CSC 103");
    }

    @Test
    void compulsoryAndCarryOversAreAlwaysIncluded() {
        var r = CourseRules.check(OFFERED, CARRY, List.of(CSC211.id(), MTH211.id()), WESLEY);
        assertThat(r.courses()).extracting(Course::code).contains("CSC 201", "CSC 203", "CSC 205", "GST 201", "CSC 103");
        assertThat(r.totalUnits()).isEqualTo(19);
    }

    @Test
    void overTheMaximumIsRefused() {
        var r = CourseRules.check(OFFERED, CARRY, List.of(CSC209.id(), MTH211.id(), CSC211.id()), WESLEY);
        assertThat(codes(r.problems())).containsExactly("over_max");
        assertThat(r.problems().get(0).message()).isEqualTo("That is 21 units. The most you can take is 20: remove an elective.");
    }

    @Test
    void underTheMinimumIsRefused() {
        var r = CourseRules.check(OFFERED, CARRY, List.of(), WESLEY);
        assertThat(codes(r.problems())).containsExactly("under_min");
        assertThat(r.problems().get(0).message()).isEqualTo("That is 13 units, 2 units under the minimum of 15. Add an elective.");
    }

    @Test
    void electivesLimit() {
        var r = CourseRules.check(OFFERED, CARRY, List.of(CSC209.id(), MTH211.id()), new Limits(15, 20, 1));
        assertThat(codes(r.problems())).containsExactly("too_many_electives");
        assertThat(r.problems().get(0).message()).isEqualTo("Choose up to 1 electives. You picked 2.");
    }

    @Test
    void notAnElective() {
        var r = CourseRules.check(OFFERED, CARRY, List.of(UUID.randomUUID(), CSC209.id(), MTH211.id()), WESLEY);
        assertThat(codes(r.problems())).containsExactly("not_an_elective");
        assertThat(CourseRules.check(OFFERED, CARRY, List.of(CSC201.id(), CSC209.id(), MTH211.id()), WESLEY).ok())
                .as("a locked course sent again is harmless").isTrue();
    }

    @Test
    void lockedLoadOverTheMaximumIsARulesConflict() {
        var r = CourseRules.check(OFFERED, CARRY, List.of(), new Limits(10, 12, 3));
        assertThat(codes(r.problems())).contains("rules_conflict").doesNotContain("over_max");
    }

    @Test
    void carryOverAlsoOfferedCountsOnce() {
        Course again = new Course(CSC201.id(), CSC201.code(), CSC201.title(), 3, Kind.CARRY_OVER);
        var r = CourseRules.check(OFFERED, List.of(again), List.of(CSC209.id(), MTH211.id(), CSC211.id()), WESLEY);
        assertThat(r.totalUnits()).isEqualTo(18);
        assertThat(r.courses().stream().filter(x -> x.code().equals("CSC 201")).findFirst().orElseThrow().kind()).isEqualTo(Kind.CARRY_OVER);
    }

    @Test
    void swapAnElective() {
        List<Course> current = CourseRules.check(OFFERED, CARRY, List.of(CSC209.id(), MTH211.id()), WESLEY).courses();
        var r = CourseRules.change(current, OFFERED, List.of(MTH211.id()), List.of(CSC211.id()), WESLEY);
        assertThat(r.ok()).isTrue();
        assertThat(r.totalUnits()).isEqualTo(18);
        assertThat(r.courses()).extracting(Course::code).contains("CSC 211").doesNotContain("MTH 211");
    }

    @Test
    void changeRules() {
        List<Course> current = CourseRules.check(OFFERED, CARRY, List.of(CSC209.id(), MTH211.id()), WESLEY).courses();
        assertThat(codes(CourseRules.change(current, OFFERED, List.of(CSC201.id()), List.of(), WESLEY).problems())).containsExactly("compulsory");
        assertThat(CourseRules.change(current, OFFERED, List.of(CSC201.id()), List.of(), WESLEY).problems().get(0).message())
                .isEqualTo("CSC 201 is compulsory, so it cannot be dropped.");
        assertThat(codes(CourseRules.change(current, OFFERED, List.of(CSC103.id()), List.of(), WESLEY).problems())).containsExactly("carry_over");
        assertThat(codes(CourseRules.change(current, OFFERED, List.of(), List.of(), WESLEY).problems())).containsExactly("no_change");
        assertThat(codes(CourseRules.change(current, OFFERED, List.of(), List.of(MTH211.id()), WESLEY).problems())).containsExactly("already_registered");
        assertThat(codes(CourseRules.change(current, OFFERED, List.of(), List.of(CSC211.id()), WESLEY).problems())).as("21 units").containsExactly("over_max");
        assertThat(codes(CourseRules.change(current, OFFERED, List.of(MTH211.id()), List.of(), WESLEY).problems())).as("15 units is fine").isEmpty();
        assertThat(codes(CourseRules.change(current, OFFERED, List.of(MTH211.id(), CSC209.id()), List.of(), WESLEY).problems())).containsExactly("under_min");
    }
}
