package com.odel.student.client;

import com.odel.student.dto.AcademicLookup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Stand-in for academic-service until it is merged (odel.student.academic-lookup=stub): the same programmes and centres for
 * every university, shaped like Wesley's. Ids are fixed per code so they stay the same between restarts.
 */
@Component
@ConditionalOnProperty(name = "odel.student.academic-lookup", havingValue = "stub")
public class StubAcademicDirectory implements AcademicDirectory {
    private static final List<Integer> LEVELS = List.of(100, 200, 300, 400);

    public static final AcademicLookup DATA = new AcademicLookup(
            new AcademicLookup.Session(id("session-2026"), "2026/2027"),
            List.of(new AcademicLookup.Programme(id("BSC-CSC-ODL"), "BSC-CSC-ODL", "B.Sc. Computer Science", LEVELS),
                    new AcademicLookup.Programme(id("BSC-IFT-ODL"), "BSC-IFT-ODL", "B.Sc. Info. Technology", LEVELS)),
            List.of(new AcademicLookup.Centre(id("AKR"), "AKR", "Akure Study Centre", "Akure", "0803 555 0117"),
                    new AcademicLookup.Centre(id("LAG"), "LAG", "Lagos (Ikeja) Study Centre", "Lagos (Ikeja)", "0803 555 0118"),
                    new AcademicLookup.Centre(id("ABJ"), "ABJ", "Abuja Study Centre", "Abuja", "0803 555 0119"),
                    new AcademicLookup.Centre(id("OND"), "OND", "Ondo (main campus)", "Ondo (main campus)", "0803 555 0120"),
                    new AcademicLookup.Centre(id("IBD"), "IBD", "Ibadan Study Centre", "Ibadan", "0803 555 0121")));

    @Override
    public AcademicLookup lookup() {
        return DATA;
    }

    private static UUID id(String code) {
        return UUID.nameUUIDFromBytes(("odel-stub-academic:" + code).getBytes());
    }
}
