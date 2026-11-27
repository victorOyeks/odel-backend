package com.odel.academic.service;

import com.odel.academic.dto.GradingDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.academic.client.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Grading rules (W-17 … W-17e) and their checks. Pure: no database, so the checks are unit-tested on their own. */
public final class Grading {
    private Grading() {}

    /** The assumed default until the university confirms (owner's decision): 5-point scale, 30% CA / 70% exam, probation below 1.00. */
    public static Rules defaults() {
        return new Rules(30, 70, 40, 5, true, List.of(
                new Band("A", 70, 100, 5, "Excellent"),
                new Band("B", 60, 69, 4, "Very good"),
                new Band("C", 50, 59, 3, "Good"),
                new Band("D", 45, 49, 2, "Fair"),
                new Band("E", 40, 44, 1, "Pass"),
                new Band("F", 0, 39, 0, "Fail (carry-over)")),
                new Standing(new BigDecimal("1.00"), 2, true, List.of(
                        new DegreeClass("First class", new BigDecimal("4.50")),
                        new DegreeClass("Second class upper", new BigDecimal("3.50")),
                        new DegreeClass("Second class lower", new BigDecimal("2.40")),
                        new DegreeClass("Third class", new BigDecimal("1.50")),
                        new DegreeClass("Pass", new BigDecimal("1.00")))));
    }

    /** Field → plain message for everything wrong; empty when the rules can be saved. */
    public static Map<String, String> check(Rules r) {
        Map<String, String> f = new LinkedHashMap<>();
        if (r.caWeight() == null) f.put("caWeight", "Enter the coursework (CA) percentage.");
        if (r.examWeight() == null) f.put("examWeight", "Enter the exam percentage.");
        if (r.caWeight() != null && r.examWeight() != null) {
            if (r.caWeight() < 0 || r.caWeight() > 100) f.put("caWeight", "Use a percentage from 0 to 100.");
            else if (r.examWeight() < 0 || r.examWeight() > 100) f.put("examWeight", "Use a percentage from 0 to 100.");
            else if (r.caWeight() + r.examWeight() != 100) {
                f.put("caWeight", "Coursework and exam add up to " + (r.caWeight() + r.examWeight()) + "%. They must add up to 100%.");
            }
        }
        int scale = r.scale() == null ? 0 : r.scale();
        if (scale != 4 && scale != 5 && scale != 7) f.put("scale", "Choose a 4, 5 or 7-point scale.");
        if (r.passMark() == null || r.passMark() < 0 || r.passMark() > 100) f.put("passMark", "Enter a pass mark from 0 to 100.");
        checkBands(r.bands(), scale, r.passMark(), f);
        checkStanding(r.standing(), scale, f);
        return f;
    }

    static void checkBands(List<Band> bands, int scale, Integer passMark, Map<String, String> f) {
        if (bands == null || bands.isEmpty()) {
            f.put("bands", "Add the grade bands.");
            return;
        }
        Set<String> grades = new HashSet<>();
        for (int i = 0; i < bands.size(); i++) {
            Band b = bands.get(i);
            String key = "bands[" + i + "]";
            if (b.grade() == null || b.grade().isBlank()) { f.put(key + ".grade", "Give the band a grade letter."); return; }
            if (!grades.add(b.grade().trim().toUpperCase())) { f.put(key + ".grade", "Grade " + b.grade().trim() + " is used twice."); return; }
            if (b.from() == null || b.to() == null) { f.put(key, "Band " + b.grade() + " needs a from and a to score."); return; }
            if (b.from() < 0 || b.to() > 100) { f.put(key, "Band " + b.grade() + " must stay between 0 and 100."); return; }
            if (b.from() > b.to()) { f.put(key, "Band " + b.grade() + " starts at " + b.from() + " but ends at " + b.to() + "."); return; }
            if (b.point() == null || b.point() < 0 || (scale > 0 && b.point() > scale)) {
                f.put(key + ".point", "Band " + b.grade() + " needs a grade point from 0 to " + (scale > 0 ? scale : 5) + "."); return;
            }
        }
        List<Band> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparing(Band::from));
        if (sorted.get(0).from() != 0) f.put("bands", "The bottom band must start at 0 (scores 0 to " + (sorted.get(0).from() - 1) + " are in no band).");
        else if (sorted.get(sorted.size() - 1).to() != 100) {
            f.put("bands", "The top band must end at 100 (scores " + (sorted.get(sorted.size() - 1).to() + 1) + " to 100 are in no band).");
        }
        for (int i = 1; i < sorted.size() && !f.containsKey("bands"); i++) {
            Band lower = sorted.get(i - 1), upper = sorted.get(i);
            if (upper.from() <= lower.to()) {
                f.put("bands", "Bands " + lower.grade() + " (" + lower.from() + "–" + lower.to() + ") and " + upper.grade() + " (" + upper.from() + "–"
                        + upper.to() + ") overlap at " + upper.from() + " to " + Math.min(lower.to(), upper.to()) + ".");
            } else if (upper.from() > lower.to() + 1) {
                int a = lower.to() + 1, b = upper.from() - 1;
                f.put("bands", (a == b ? "Score " + a + " is" : "Scores " + a + " to " + b + " are") + " in no band.");
            } else if (upper.point() < lower.point()) {
                f.put("bands", "Band " + upper.grade() + " has higher scores than " + lower.grade() + " but fewer grade points.");
            }
        }
        if (!f.containsKey("bands") && passMark != null && passMark >= 0 && passMark <= 100) {
            sorted.stream().filter(b -> b.from() <= passMark && passMark <= b.to()).findFirst()
                    .filter(b -> b.point() == 0)
                    .ifPresent(b -> f.put("passMark", "The pass mark is " + passMark + " but scores of " + passMark + " get 0 points in band " + b.grade() + "."));
        }
    }

    static void checkStanding(Standing s, int scale, Map<String, String> f) {
        if (s == null) { f.put("standing", "Set the standing and degree class rules."); return; }
        BigDecimal max = BigDecimal.valueOf(scale > 0 ? scale : 5);
        if (s.probationBelow() == null || s.probationBelow().signum() < 0 || s.probationBelow().compareTo(max) > 0) {
            f.put("standing.probationBelow", "Enter a CGPA from 0.00 to " + max + ".00.");
        }
        if (s.withdrawAfterProbationSemesters() == null || s.withdrawAfterProbationSemesters() < 1 || s.withdrawAfterProbationSemesters() > 6) {
            f.put("standing.withdrawAfterProbationSemesters", "Choose from 1 to 6 semesters.");
        }
        List<DegreeClass> classes = s.classes();
        if (classes == null || classes.isEmpty()) { f.put("standing.classes", "Add the classes of degree."); return; }
        for (int i = 0; i < classes.size(); i++) {
            DegreeClass c = classes.get(i);
            if (c.name() == null || c.name().isBlank() || c.from() == null || c.from().signum() < 0 || c.from().compareTo(max) > 0) {
                f.put("standing.classes[" + i + "]", "Each class needs a name and a CGPA from 0.00 to " + max + ".00."); return;
            }
            if (i > 0 && c.from().compareTo(classes.get(i - 1).from()) >= 0) {
                f.put("standing.classes[" + i + "]", c.name() + " must start below " + classes.get(i - 1).name() + " (" + classes.get(i - 1).from() + ")."); return;
            }
        }
    }

    /** "Default 5-point scale (30% CA / 70% exam)". */
    public static String summary(Rules r, boolean programme) {
        return (programme ? "Own rules: " : "Default ") + r.scale() + "-point scale (" + r.caWeight() + "% CA / " + r.examWeight() + "% exam)";
    }

    /** The check line under the bands (W-17): an example total in the top band. */
    public static String example(Rules r) {
        int ca = Math.round(r.caWeight() * 0.8f), exam = Math.round(r.examWeight() * 0.73f);
        int total = ca + exam;
        return r.bands().stream().filter(b -> b.from() <= total && total <= b.to()).findFirst()
                .map(b -> "CA " + ca + " + exam " + exam + " = " + total + " → " + b.grade() + " (" + b.point() + (b.point() == 1 ? " point)" : " points)"))
                .orElse(null);
    }
}
