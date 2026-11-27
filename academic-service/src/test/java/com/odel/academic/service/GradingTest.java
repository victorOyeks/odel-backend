package com.odel.academic.service;

import com.odel.academic.dto.GradingDtos.Band;
import com.odel.academic.dto.GradingDtos.DegreeClass;
import com.odel.academic.dto.GradingDtos.Rules;
import com.odel.academic.dto.GradingDtos.Standing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradingTest {
    Rules d = Grading.defaults();

    Rules with(Integer ca, Integer exam, List<Band> bands) {
        return new Rules(ca, exam, d.passMark(), d.scale(), true, bands, d.standing());
    }

    @Test
    void defaultRulesPassAndReadWell() {
        assertThat(Grading.check(d)).isEmpty();
        assertThat(Grading.summary(d, false)).isEqualTo("Default 5-point scale (30% CA / 70% exam)");
        assertThat(Grading.example(d)).isEqualTo("CA 24 + exam 51 = 75 → A (5 points)");
    }

    @Test
    void weightsMustAddUpTo100() {
        assertThat(Grading.check(with(40, 50, d.bands())))
                .containsEntry("caWeight", "Coursework and exam add up to 90%. They must add up to 100%.");
        assertThat(Grading.check(with(40, 60, d.bands()))).isEmpty();
        assertThat(Grading.check(with(120, -20, d.bands()))).containsKey("caWeight");
        assertThat(Grading.check(with(null, 70, d.bands()))).containsKey("caWeight");
    }

    @Test
    void bandsMustCoverEveryScoreOnce() {
        List<Band> overlap = new ArrayList<>(d.bands());
        overlap.set(2, new Band("C", 50, 62, 3, "Good"));
        assertThat(Grading.check(with(30, 70, overlap)).get("bands")).isEqualTo("Bands C (50–62) and B (60–69) overlap at 60 to 62.");

        List<Band> gap = new ArrayList<>(d.bands());
        gap.set(4, new Band("E", 41, 44, 1, "Pass"));
        assertThat(Grading.check(with(30, 70, gap)).get("bands")).isEqualTo("Score 40 is in no band.");

        List<Band> top = new ArrayList<>(d.bands());
        top.set(0, new Band("A", 70, 95, 5, "Excellent"));
        assertThat(Grading.check(with(30, 70, top)).get("bands")).contains("must end at 100");

        List<Band> bottom = new ArrayList<>(d.bands());
        bottom.set(5, new Band("F", 5, 39, 0, "Fail"));
        assertThat(Grading.check(with(30, 70, bottom)).get("bands")).contains("must start at 0");

        List<Band> twice = new ArrayList<>(d.bands());
        twice.set(1, new Band("A", 60, 69, 4, "Very good"));
        assertThat(Grading.check(with(30, 70, twice))).containsEntry("bands[1].grade", "Grade A is used twice.");

        List<Band> backwards = new ArrayList<>(d.bands());
        backwards.set(1, new Band("B", 69, 60, 4, "Very good"));
        assertThat(Grading.check(with(30, 70, backwards))).containsKey("bands[1]");

        List<Band> points = new ArrayList<>(d.bands());
        points.set(1, new Band("B", 60, 69, 6, "Very good"));
        assertThat(Grading.check(with(30, 70, points))).containsKey("bands[1].point");
    }

    @Test
    void passMarkMustEarnPoints() {
        Rules r = new Rules(30, 70, 35, 5, true, d.bands(), d.standing());
        assertThat(Grading.check(r).get("passMark")).contains("get 0 points");
    }

    @Test
    void standingClassesGoDown() {
        Standing s = new Standing(new BigDecimal("1.00"), 2, true, List.of(new DegreeClass("First class", new BigDecimal("4.50")),
                new DegreeClass("Second class upper", new BigDecimal("4.60"))));
        assertThat(Grading.check(new Rules(30, 70, 40, 5, true, d.bands(), s))).containsKey("standing.classes[1]");
        Standing probation = new Standing(new BigDecimal("6.00"), 0, true, d.standing().classes());
        assertThat(Grading.check(new Rules(30, 70, 40, 5, true, d.bands(), probation)))
                .containsKeys("standing.probationBelow", "standing.withdrawAfterProbationSemesters");
    }
}
