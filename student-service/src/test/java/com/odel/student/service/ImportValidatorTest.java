package com.odel.student.service;

import com.odel.student.client.StubAcademicDirectory;
import com.odel.student.dto.AcademicLookup;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportValidatorTest {
    static final String HEADER = "matric_number,surname,first_name,other_names,programme_code,level,study_centre,email,phone\n";
    final AcademicLookup lookup = StubAcademicDirectory.DATA;

    ImportValidator.Result run(String csv, Map<String, ImportValidator.Existing> existing, Set<String> taken) throws Exception {
        return ImportValidator.validate(CsvParser.parse(new StringReader(csv)), lookup, existing, c -> taken);
    }

    ImportValidator.Result run(String csv) throws Exception {
        return run(csv, Map.of(), Set.of());
    }

    static List<String> messages(ImportValidator.Result r, int row) {
        return r.problems().stream().filter(p -> p.row() == row).map(ImportValidator.Problem::message).toList();
    }

    @Test
    void goodRowsAreNewAndNormalised() throws Exception {
        var r = run(HEADER + "wu/odl/24/0153 ,Okafor,Adeola,,BSC-CSC-ODL,200L,Akure,Adeola.Okafor@Wesley.test,+234 803 412 4417\n"
                + "WU/ODL/24/0154,Bello,Musa,,bsc-ift-odl,Level 100,ABJ,musa@wesley.test,\n");
        assertThat(r.added()).isEqualTo(2);
        assertThat(r.errors()).isZero();
        var d = r.rows().get(0).data();
        assertThat(d.matricNumber()).isEqualTo("WU/ODL/24/0153");
        assertThat(d.email()).isEqualTo("adeola.okafor@wesley.test");
        assertThat(d.phone()).isEqualTo("0803 412 4417");
        assertThat(d.level()).isEqualTo(200);
        assertThat(d.centre().code()).isEqualTo("AKR");
        assertThat(d.entrySession()).isEqualTo("2026/2027");
        assertThat(r.rows().get(1).data().programme().code()).isEqualTo("BSC-IFT-ODL");
    }

    @Test
    void eachKindOfError() throws Exception {
        var r = run(HEADER
                + "WU/ODL/24/0201,Musa,Ibrahim,,BSC-CSC-ODL,200,Ikeja,ibrahim@wesley.test,\n"   // 2 bad centre
                + ",Eze,Chioma,,BSC-CSC-ODL,200,Akure,chioma@wesley.test,\n"                    // 3 matric missing
                + "WU/ODL/24/1011,Sani,Fatima,,CSC-ODL,200,Akure,fatima@wesley.test,\n"         // 4 programme unknown
                + "WU/ODL/24/1012,Ade,Bola,,BSC-CSC-ODL,500,Akure,bola@wesley.test,\n"          // 5 level not offered
                + "WU/ODL/24/1013,,,,BSC-CSC-ODL,200,Akure,x@wesley.test,\n"                    // 6 name missing
                + "WU/ODL/24/1014,Obi,Ken,,BSC-CSC-ODL,200,Akure,not-an-email,\n"               // 7 bad email
                + "WU/ODL/24/1015,Obi,Kene,,BSC-CSC-ODL,2nd,Akure,kene@wesley.test,\n");        // 8 bad level
        assertThat(r.errors()).isEqualTo(7);
        assertThat(r.added()).isZero();
        assertThat(messages(r, 2)).contains("Study centre \"Ikeja\" not found. Did you mean \"Lagos (Ikeja)\"?");
        assertThat(messages(r, 3)).contains("Matric number is missing");
        assertThat(messages(r, 4)).contains("Programme code \"CSC-ODL\" unknown. Expected \"BSC-CSC-ODL\"");
        assertThat(messages(r, 5)).contains("Level 500 is not offered for B.Sc. Computer Science (100L–400L)");
        assertThat(messages(r, 6)).contains("Name is missing (surname and first name)");
        assertThat(messages(r, 7)).contains("Email \"not-an-email\" is not a valid address");
        assertThat(messages(r, 8).get(0)).startsWith("Level \"2nd\" is not a level");
    }

    @Test
    void duplicatesInTheFileAreErrorsOnEveryCopy() throws Exception {
        var r = run(HEADER
                + "WU/ODL/24/0153,Okafor,Adeola,,BSC-CSC-ODL,200,Akure,a@wesley.test,\n"
                + "WU/ODL/24/0154,Bello,Tunde,,BSC-CSC-ODL,200,Akure,same@wesley.test,\n"
                + "WU/ODL/24/0153,Okafor,Ade,,BSC-CSC-ODL,200,Akure,b@wesley.test,\n"
                + "WU/ODL/24/0155,Bello,Tola,,BSC-CSC-ODL,200,Akure,same@wesley.test,\n");
        assertThat(r.errors()).isEqualTo(4);
        assertThat(messages(r, 2)).contains("Matric number appears twice in this file (rows 2 and 4)");
        assertThat(messages(r, 3).get(0)).startsWith("Email same@wesley.test appears twice in this file (rows 3 and 5)");
    }

    @Test
    void warningsLetTheRowIn() throws Exception {
        var r = run(HEADER
                + "WU/ODL/24/0688,Adewale,Bayo,,BSC-CSC-ODL,200,Akure,bayo@wesley.test,0803-12\n"
                + "WU/ODL/24/0689,Akin,Sade,,BSC-CSC-ODL,200,Akure,,\n");
        assertThat(r.added()).isEqualTo(2);
        assertThat(r.warnings()).isEqualTo(2);
        assertThat(messages(r, 2)).contains("Phone \"0803-12\" is too short; it is left blank");
        assertThat(messages(r, 3).get(0)).startsWith("No email");
        assertThat(r.rows().get(1).data().email()).isNull();
    }

    @Test
    void emailUsedByAnotherAccount() throws Exception {
        var r = run(HEADER + "WU/ODL/24/0153,Okafor,Adeola,,BSC-CSC-ODL,200,Akure,ngozi@wesley.test,\n", Map.of(), Set.of("ngozi@wesley.test"));
        assertThat(r.errors()).isEqualTo(1);
        assertThat(messages(r, 2)).contains("Email ngozi@wesley.test is already used by another account");
    }

    @Test
    void knownMatricNumbersUpdateOrStayTheSame() throws Exception {
        UUID id = UUID.randomUUID(), id2 = UUID.randomUUID();
        var existing = Map.of(
                "WU/ODL/24/0153", new ImportValidator.Existing(id, "Okafor", "Adeola", null, "adeola@wesley.test", null, null, null, "BSC-CSC-ODL", 100, "AKR", "2024/2025", "ACTIVE"),
                "WU/ODL/24/0154", new ImportValidator.Existing(id2, "Bello", "Tunde", null, "tunde@wesley.test", null, null, null, "BSC-CSC-ODL", 200, "AKR", "2024/2025", "INVITED"));
        var r = run(HEADER
                + "WU/ODL/24/0153,Okafor,Adeola,,BSC-CSC-ODL,200,Akure,new@wesley.test,\n"
                + "WU/ODL/24/0154,Bello,Tunde,,BSC-CSC-ODL,200,AKR,,\n", existing, Set.of());
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.unchanged()).isEqualTo(1);
        assertThat(r.rows().get(0).existingId()).isEqualTo(id);
        assertThat(r.rows().get(0).data().email()).isEqualTo("adeola@wesley.test"); // active: email kept
        assertThat(r.rows().get(0).data().level()).isEqualTo(200);
        assertThat(r.rows().get(0).data().entrySession()).isEqualTo("2024/2025");
        assertThat(messages(r, 2)).anyMatch(m -> m.startsWith("Email not changed"));
        assertThat(r.rows().get(1).data().email()).isEqualTo("tunde@wesley.test"); // a blank never wipes
    }

    @Test
    void unusualMatricShapeIsAWarning() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 6; i++) csv.append("WU/ODL/24/01").append(10 + i).append(",S").append(i).append(",F,,BSC-CSC-ODL,200,Akure,s").append(i).append("@wesley.test,\n");
        csv.append("WU-ODL-24-0199,Odd,One,,BSC-CSC-ODL,200,Akure,odd@wesley.test,\n");
        var r = run(csv.toString());
        assertThat(r.added()).isEqualTo(7);
        assertThat(messages(r, 8).get(0)).contains("looks different from most in this file");
    }

    @Test
    void fileLevelProblems() {
        assertThatThrownBy(() -> run("")).isInstanceOf(ImportValidator.FileProblem.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> run("a,b,c\n1,2,3\n")).isInstanceOf(ImportValidator.FileProblem.class).hasMessageContaining("column names");
        assertThatThrownBy(() -> run("matric_number,surname,first_name\nX/1,A,B\n"))
                .isInstanceOfSatisfying(ImportValidator.FileProblem.class, p -> {
                    assertThat(p.code).isEqualTo("missing_columns");
                    assertThat(p.fields).containsKeys("programme_code", "level", "study_centre");
                });
        assertThatThrownBy(() -> run(HEADER)).isInstanceOfSatisfying(ImportValidator.FileProblem.class, p -> assertThat(p.code).isEqualTo("empty_file"));
    }

    @Test
    void looseHeaders() {
        assertThat(ImportValidator.column(" Matric No ")).isEqualTo("matric_number");
        assertThat(ImportValidator.column("Study Centre")).isEqualTo("study_centre");
        assertThat(ImportValidator.column("E-mail")).isEqualTo("email");
    }

    @Test
    void theTemplateChecksClean() throws Exception {
        var r = run(ImportValidator.template());
        assertThat(r.errors()).isZero();
        assertThat(r.added()).isEqualTo(1);
    }

    @Test
    void threeThousandFiveHundredRowsCheckQuickly() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 3504; i++) csv.append("WU/ODL/24/").append(String.format("%05d", i)).append(",Sur").append(i).append(",First,,BSC-CSC-ODL,200,Akure,s")
                .append(i).append("@wesley.test,08034124417\n");
        long t = System.currentTimeMillis();
        var r = run(csv.toString());
        assertThat(r.added()).isEqualTo(3504);
        assertThat(System.currentTimeMillis() - t).isLessThan(5000);
    }
}
