package com.odel.academic.service;

import com.odel.academic.dto.SemesterInternalDtos.Semesters;
import com.odel.academic.dto.SemesterInternalDtos.SemesterItem;
import com.odel.academic.dto.SemesterInternalDtos.Window;
import com.odel.academic.entity.AcademicSession;
import com.odel.academic.entity.Semester;
import com.odel.academic.repository.SemesterRepository;
import com.odel.academic.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Semesters with their session's name, and the current one: the active session's first semester not yet ended, else its last. */
@Service
public class SemesterDirectory {
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private final SessionRepository sessions;
    private final SemesterRepository semesters;
    private final Clock clock;

    public SemesterDirectory(SessionRepository sessions, SemesterRepository semesters, Clock clock) {
        this.sessions = sessions;
        this.semesters = semesters;
        this.clock = clock;
    }

    public static String name(int number) {
        return number == 1 ? "1st semester" : number == 2 ? "2nd semester" : number + "th semester";
    }

    @Transactional(readOnly = true)
    public Semesters all() {
        List<AcademicSession> all = sessions.newestFirst();
        Map<UUID, List<Semester>> bySession = semesters.findBySessionIdIn(all.stream().map(AcademicSession::getId).toList()).stream()
                .collect(Collectors.groupingBy(Semester::getSessionId));
        List<SemesterItem> items = new ArrayList<>();
        AcademicSession active = all.stream().filter(s -> s.getStatus() == AcademicSession.Status.ACTIVE).findFirst().orElse(null);
        UUID current = null;
        LocalDate today = LocalDate.now(clock.withZone(LAGOS));
        for (AcademicSession s : all) {
            List<Semester> sems = new ArrayList<>(bySession.getOrDefault(s.getId(), List.of()));
            sems.sort(Comparator.comparingInt(Semester::getNumber));
            for (Semester x : sems) {
                items.add(new SemesterItem(x.getId(), s.getId(), s.getName(), s.getStatus().name(), x.getNumber(), name(x.getNumber()),
                        s.getName() + " · " + name(x.getNumber()), x.getStartsOn(), x.getEndsOn(),
                        new Window(x.getRegOpensOn(), x.getRegClosesOn(), x.getLateRegClosesOn())));
            }
            if (active != null && s.getId().equals(active.getId()) && !sems.isEmpty()) {
                current = sems.stream().filter(x -> x.getEndsOn() == null || !x.getEndsOn().isBefore(today)).findFirst()
                        .orElse(sems.get(sems.size() - 1)).getId();
            }
        }
        return new Semesters(active == null ? null : active.getId(), current, items);
    }
}
