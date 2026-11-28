package com.odel.registration.service;

import com.odel.registration.dto.RegistrationDtos.Problem;

import java.util.*;

/**
 * The course rules of one registration: compulsory courses and pinned carry-overs always count, the student picks electives
 * up to the programme's limit, and the total must sit between the minimum and the maximum units. Pure, so it is unit tested.
 */
public final class CourseRules {
    private CourseRules() {}

    public enum Kind { COMPULSORY, ELECTIVE, CARRY_OVER }

    public record Course(UUID id, String code, String title, int units, Kind kind) {}

    public record Limits(int minUnits, int maxUnits, int maxElectives) {}

    public record Result(List<Course> courses, int totalUnits, int electiveCount, List<Problem> problems) {
        public boolean ok() { return problems.isEmpty(); }
    }

    /**
     * @param offered    compulsory and elective courses offered to the student this semester
     * @param carryOvers open carry-overs whose course runs this semester (pinned)
     * @param electiveIds the electives the student picked
     */
    public static Result check(List<Course> offered, List<Course> carryOvers, Collection<UUID> electiveIds, Limits limits) {
        List<Problem> problems = new ArrayList<>();
        Map<UUID, Course> byId = new LinkedHashMap<>();
        for (Course c : carryOvers) byId.putIfAbsent(c.id(), c);   // a carry-over that is also offered counts once, as the carry-over
        for (Course c : offered) if (c.kind() == Kind.COMPULSORY) byId.putIfAbsent(c.id(), c);
        Map<UUID, Course> electives = new LinkedHashMap<>();
        for (Course c : offered) if (c.kind() == Kind.ELECTIVE && !byId.containsKey(c.id())) electives.put(c.id(), c);

        int lockedUnits = byId.values().stream().mapToInt(Course::units).sum();
        Set<UUID> picked = new LinkedHashSet<>(electiveIds == null ? List.of() : electiveIds);
        int electiveCount = 0;
        for (UUID id : picked) {
            if (byId.containsKey(id)) continue;   // a locked course sent again is harmless
            Course e = electives.get(id);
            if (e == null) {
                problems.add(new Problem("not_an_elective", "That course is not an elective for your programme this semester."));
                continue;
            }
            byId.put(id, e);
            electiveCount++;
        }
        int total = byId.values().stream().mapToInt(Course::units).sum();
        if (lockedUnits > limits.maxUnits()) {
            problems.add(new Problem("rules_conflict", "Your compulsory and carry-over courses come to " + lockedUnits + " units, over the maximum of "
                    + limits.maxUnits() + ". Contact your study centre: the ODeL office needs to adjust the unit limits."));
        } else if (total > limits.maxUnits()) {
            problems.add(new Problem("over_max", "That is " + total + " units. The most you can take is " + limits.maxUnits() + ": remove an elective."));
        }
        if (electiveCount > limits.maxElectives()) {
            problems.add(new Problem("too_many_electives", "Choose up to " + limits.maxElectives() + " electives. You picked " + electiveCount + "."));
        }
        if (total < limits.minUnits()) {
            int gap = limits.minUnits() - total;
            problems.add(new Problem("under_min", "That is " + total + " units, " + gap + (gap == 1 ? " unit" : " units") + " under the minimum of "
                    + limits.minUnits() + ". Add an elective."));
        }
        List<Course> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparing((Course c) -> rank(c.kind())).thenComparing(Course::code));
        return new Result(ordered, total, electiveCount, problems);
    }

    /** Compulsory first, then carry-overs, then electives (the slip's order). */
    static int rank(Kind k) {
        return switch (k) { case COMPULSORY -> 0; case CARRY_OVER -> 1; case ELECTIVE -> 2; };
    }

    /**
     * A course change on a registration: only electives may be dropped, only offered electives not already taken may be added,
     * and the result must keep the rules. Returns the problems with the dropping and adding itself, then the rules of the result.
     */
    public static Result change(List<Course> current, List<Course> offered, Collection<UUID> drop, Collection<UUID> add, Limits limits) {
        List<Problem> problems = new ArrayList<>();
        Map<UUID, Course> now = new LinkedHashMap<>();
        current.forEach(c -> now.put(c.id(), c));
        Set<UUID> dropSet = new LinkedHashSet<>(drop == null ? List.of() : drop);
        Set<UUID> addSet = new LinkedHashSet<>(add == null ? List.of() : add);
        if (dropSet.isEmpty() && addSet.isEmpty()) problems.add(new Problem("no_change", "Choose a course to drop or add."));
        for (UUID id : dropSet) {
            Course c = now.get(id);
            if (c == null) problems.add(new Problem("not_registered", "You can only drop a course you are registered for."));
            else if (c.kind() == Kind.COMPULSORY) problems.add(new Problem("compulsory", c.code() + " is compulsory, so it cannot be dropped."));
            else if (c.kind() == Kind.CARRY_OVER) problems.add(new Problem("carry_over", c.code() + " is a carry-over, so it cannot be dropped."));
        }
        Map<UUID, Course> electives = new LinkedHashMap<>();
        offered.stream().filter(c -> c.kind() == Kind.ELECTIVE).forEach(c -> electives.put(c.id(), c));
        for (UUID id : addSet) {
            if (now.containsKey(id) && !dropSet.contains(id)) problems.add(new Problem("already_registered", now.get(id).code() + " is already on your registration."));
            else if (!electives.containsKey(id)) problems.add(new Problem("not_an_elective", "That course is not an elective for your programme this semester."));
        }
        if (!problems.isEmpty()) return new Result(current, current.stream().mapToInt(Course::units).sum(), 0, problems);
        List<Course> locked = current.stream().filter(c -> c.kind() != Kind.ELECTIVE).toList();
        List<UUID> keptElectives = new ArrayList<>(current.stream().filter(c -> c.kind() == Kind.ELECTIVE && !dropSet.contains(c.id())).map(Course::id).toList());
        keptElectives.addAll(addSet);
        // The locked courses stay exactly as registered (a compulsory course added to the catalogue later does not appear by itself).
        List<Course> offeredNow = new ArrayList<>(locked.stream().filter(c -> c.kind() == Kind.COMPULSORY).toList());
        offeredNow.addAll(electives.values());
        // electives already on the registration but no longer offered stay valid
        current.stream().filter(c -> c.kind() == Kind.ELECTIVE && !electives.containsKey(c.id())).forEach(offeredNow::add);
        return check(offeredNow, locked.stream().filter(c -> c.kind() == Kind.CARRY_OVER).toList(), keptElectives, limits);
    }
}
