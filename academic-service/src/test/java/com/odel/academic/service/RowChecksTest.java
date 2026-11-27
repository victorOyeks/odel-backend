package com.odel.academic.service;

import com.odel.academic.dto.CentreDtos.CentreIn;
import com.odel.academic.dto.CentreDtos.RoomIn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The checks each form and each CSV row goes through. */
class RowChecksTest {
    @Test
    void courseCodes() {
        assertThat(Codes.course("csc201")).isEqualTo("CSC 201");
        assertThat(Codes.course(" CSC  201 ")).isEqualTo("CSC 201");
        assertThat(Codes.course("GST 101A")).isEqualTo("GST 101A");
        assertThat(Codes.course("201 CSC")).isNull();
        assertThat(CourseService.check("CSC 201", "Data Structures", 3, 200, 1)).isEmpty();
        assertThat(CourseService.check("CSC", "", 0, 250, 3)).containsOnlyKeys("code", "title", "units", "level", "semester");
    }

    @Test
    void csvCells() {
        assertThat(ImportService.level("200L")).isEqualTo(200);
        assertThat(ImportService.level("200")).isEqualTo(200);
        assertThat(ImportService.semester("First")).isEqualTo(1);
        assertThat(ImportService.semester("2nd")).isEqualTo(2);
        assertThat(ImportService.semester("third")).isNull();
        assertThat(ImportService.levels("100;200")).containsExactly(100, 200);
        assertThat(ImportService.levels("")).isEmpty();
        assertThat(ImportService.levels("one")).isNull();
        assertThat(CourseService.kind("Core")).isNotNull();
        assertThat(CourseService.kind("optional")).isNull();
    }

    @Test
    void programmes() {
        assertThat(StructureService.checkProgramme("B.Sc. Computer Science", "BSC-CSC", "B.Sc.", 4, List.of(100, 200))).isEmpty();
        assertThat(StructureService.checkProgramme("B.Sc. Computer Science", "BSC CSC", "B.Sc.", 8, List.of(100))).containsOnlyKeys("code", "durationYears");
        assertThat(StructureService.checkProgramme("X", "X", "B.Sc.", 4, List.of(500))).containsKey("entryLevels");
    }

    @Test
    void centresAndRooms() {
        assertThat(CentreService.checkCentre(new CentreIn("Akure Study Centre", "AKR", "Oba Adesida Road", "Ondo", "0803 123 4567"))).isEmpty();
        assertThat(CentreService.checkCentre(new CentreIn("", "A", null, "Atlantis", "12345"))).containsOnlyKeys("name", "code", "state", "phone");
        assertThat(CentreService.checkRoom(new RoomIn("Room 1", 30, 30))).isEmpty();
        assertThat(CentreService.checkRoom(new RoomIn("Room 1", 30, 31))).containsEntry("computers", "A room cannot have more computers than seats.");
    }

    @Test
    void staffingSummary() {
        assertThat(StaffingService.summary(212, 19, 28, 3)).isEqualTo("19 courses still need a lecturer · 3 centres need a coordinator");
        assertThat(StaffingService.summary(1, 0, 1, 0)).isEqualTo("All 1 course has a lecturer");
        assertThat(StaffingService.shortDepartment("Department of Computer Science")).isEqualTo("Computer Science");
    }
}
