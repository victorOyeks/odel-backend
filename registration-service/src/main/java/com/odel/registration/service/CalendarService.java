package com.odel.registration.service;

import com.odel.common.ApiException;
import com.odel.common.tenancy.TenantContext;
import com.odel.registration.client.AcademicClient;
import com.odel.registration.dto.PeerDtos.Calendar;
import com.odel.registration.dto.PeerDtos.SemesterInfo;
import com.odel.registration.dto.RegistrationDtos.SemesterView;
import com.odel.registration.dto.RegistrationDtos.WindowView;
import com.odel.registration.entity.SemesterSettings;
import com.odel.registration.repository.SemesterSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Which semester a student registers for, and its window today. The active session's semesters with a registration window:
 * the first whose course-change deadline has not passed, otherwise the last one.
 */
@Service
public class CalendarService {
    private final AcademicClient academic;
    private final SemesterSettingsRepository semesterSettings;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CalendarService(AcademicClient academic, SemesterSettingsRepository semesterSettings, TransactionTemplate tx, Clock clock) {
        this.academic = academic;
        this.semesterSettings = semesterSettings;
        this.tx = tx;
        this.clock = clock;
    }

    /** A semester and its window as of today. {@code semester} null = no active session or no semester with a window. */
    public record Choice(SemesterInfo semester, Window window, LocalDate today) {
        public boolean none() { return semester == null; }

        public UUID semesterId() { return semester == null ? null : semester.id(); }
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(Dates.LAGOS));
    }

    /** {@code semesterId} null = the current registration semester. Another university's id, or one outside the active session → 404. */
    public Choice pick(UUID semesterId) {
        LocalDate today = today();
        if (semesterId != null) {
            SemesterInfo s;
            try {
                s = academic.semester(semesterId);
            } catch (ApiException e) {
                if (e.status() == HttpStatus.NOT_FOUND) throw ApiException.notFound();
                throw e;
            }
            return new Choice(s, window(s, today), today);
        }
        Calendar cal = academic.calendar();
        if (cal == null || cal.currentSession() == null || cal.currentSession().semesters() == null) {
            return new Choice(null, Window.of(today, null, null, null, null), today);
        }
        List<SemesterInfo> withWindow = cal.currentSession().semesters().stream().filter(s -> s.regOpensOn() != null && s.regClosesOn() != null).toList();
        if (withWindow.isEmpty()) {
            SemesterInfo first = cal.currentSession().semesters().isEmpty() ? null : cal.currentSession().semesters().get(0);
            return new Choice(first, Window.of(today, null, null, null, null), today);
        }
        for (SemesterInfo s : withWindow) {
            Window w = window(s, today);
            if (w.changesOpen(today)) return new Choice(s, w, today);
        }
        SemesterInfo last = withWindow.get(withWindow.size() - 1);
        return new Choice(last, window(last, today), today);
    }

    public Window window(SemesterInfo s, LocalDate today) {
        LocalDate changes = tx.execute(t -> semesterSettings.forSemester(TenantContext.require(), s.id()).map(SemesterSettings::getChangesCloseOn).orElse(null));
        return Window.of(today, s.regOpensOn(), s.regClosesOn(), s.lateRegClosesOn(), changes);
    }

    public static SemesterView view(SemesterInfo s) {
        if (s == null) return null;
        return new SemesterView(s.id(), s.sessionId(), s.sessionName(), s.number(), label(s.sessionName(), s.number()), s.name());
    }

    /** "2026/2027 · 1st semester" */
    public static String label(String sessionName, int number) {
        return sessionName + " · " + (number == 1 ? "1st" : "2nd") + " semester";
    }

    public static WindowView view(Window w, LocalDate registerBy) {
        return new WindowView(w.state().name(), w.opensOn(), w.closesOn(), w.lateClosesOn(), w.changesCloseOn(), w.label(), registerBy);
    }
}
