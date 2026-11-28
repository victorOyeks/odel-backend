package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.PeerClient;
import com.odel.content.dto.PeerDtos.*;
import com.odel.content.entity.Offering;
import com.odel.content.repository.OfferingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Semesters (academic-service) and offerings: one per course and semester, made the first time anyone opens it. */
@Service
public class Directory {
    private final PeerClient peers;
    private final OfferingRepository offerings;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public Directory(PeerClient peers, OfferingRepository offerings, JdbcTemplate jdbc, Clock clock) {
        this.peers = peers;
        this.offerings = offerings;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(Labels.LAGOS));
    }

    public List<SemesterItem> semesters() {
        Semesters s = peers.semesters();
        return s == null || s.items() == null ? List.of() : s.items();
    }

    public SemesterItem semester(UUID id) {
        return semesters().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "not_found", "We could not find that semester."));
    }

    /** The given semester, else the current one (academic-service's choice), else null when no session is set up. */
    public SemesterItem pick(UUID semesterId) {
        if (semesterId != null) return semester(semesterId);
        Semesters s = peers.semesters();
        if (s == null || s.items() == null || s.items().isEmpty()) return null;
        if (s.currentSemesterId() != null) {
            Optional<SemesterItem> cur = s.items().stream().filter(x -> x.id().equals(s.currentSemesterId())).findFirst();
            if (cur.isPresent()) return cur.get();
        }
        return s.items().get(0);
    }

    /** The semesters of the same session as the given one (the T-01 semester switcher). */
    public List<SemesterItem> sessionSemesters(SemesterItem sem) {
        return semesters().stream().filter(s -> s.sessionId().equals(sem.sessionId())).sorted(Comparator.comparingInt(SemesterItem::number)).toList();
    }

    /** The offering of a course in a semester, made now if it does not exist yet. */
    public Offering ensure(CourseRef c, SemesterItem sem) {
        Optional<Offering> found = offerings.find(c.id(), sem.id());
        if (found.isPresent()) return found.get();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into offerings (id, tenant_id, course_id, course_code, course_title, units, level, semester_id, semester_number, session_id,
                    session_name, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (tenant_id, course_id, semester_id) do nothing""",
                UUID.randomUUID(), TenantContext.require(), c.id(), c.code(), c.title(), c.units(), c.level(), sem.id(), sem.number(),
                sem.sessionId(), sem.sessionName(), now, now);
        return offerings.find(c.id(), sem.id()).orElseThrow();
    }

    public Offering offering(UUID id) {
        return offerings.findInTenant(id).orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "not_found",
                "We could not find that course. Go back to My courses and open it again."));
    }

    /** The course as the catalogue has it, with its staff in the session (null when the catalogue no longer has it). */
    public TaughtCourse taught(UUID sessionId, UUID courseId) {
        Teaching t = peers.teaching(sessionId, null);
        if (t == null || t.items() == null) return null;
        return t.items().stream().filter(x -> x.course().id().equals(courseId)).findFirst().orElse(null);
    }

    public UUID lecturerOf(Offering o) {
        TaughtCourse t = taught(o.getSessionId(), o.getCourseId());
        return t == null ? null : t.lecturerId();
    }
}
