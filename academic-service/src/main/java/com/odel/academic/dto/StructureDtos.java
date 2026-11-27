package com.odel.academic.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: Faculties, departments and programmes (W-12 …). */
public final class StructureDtos {
    private StructureDtos() {}

    public record ProgrammeRow(UUID id, String name, String code, String degree, int durationYears, List<Integer> levels,
                               List<Integer> entryLevels, Integer minUnitsToGraduate, String status, long courseCount) {}

    public record DepartmentRow(UUID id, String name, String code, int programmeCount, List<ProgrammeRow> programmes) {}

    public record FacultyRow(UUID id, String name, String code, int departmentCount, List<DepartmentRow> departments) {}

    public record Totals(int faculties, int departments, int programmes) {}

    public record Tree(List<FacultyRow> faculties, Totals totals) {}

    public record Ref(UUID id, String name) {}

    public record LevelRow(int level, int semester, int courses, int units, int lecturersAssigned) {}

    public record ProgrammeDetail(UUID id, String name, String code, String degree, int durationYears, String durationLabel, List<Integer> levels,
                                  List<Integer> entryLevels, String entryLevelsLabel, Integer minUnitsToGraduate, String status, String gradingRules,
                                  Ref department, Ref faculty, List<LevelRow> byLevel) {}

    public record FacultyIn(String name, String code) {}

    public record DepartmentIn(UUID facultyId, String name, String code) {}

    public record ProgrammeIn(UUID departmentId, String name, String code, String degree, Integer durationYears, List<Integer> entryLevels,
                              Integer minUnitsToGraduate) {}

    public record FacultyView(UUID id, String name, String code) {
        public static FacultyView of(com.odel.academic.entity.Faculty f) {
            return new FacultyView(f.getId(), f.getName(), f.getCode());
        }
    }

    public record DepartmentView(UUID id, UUID facultyId, String name, String code) {
        public static DepartmentView of(com.odel.academic.entity.Department d) {
            return new DepartmentView(d.getId(), d.getFacultyId(), d.getName(), d.getCode());
        }
    }
}
