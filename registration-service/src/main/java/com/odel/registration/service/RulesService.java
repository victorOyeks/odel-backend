package com.odel.registration.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import com.odel.registration.client.AcademicClient;
import com.odel.registration.dto.PeerDtos;
import com.odel.registration.dto.StaffDtos.*;
import com.odel.registration.entity.SemesterSettings;
import com.odel.registration.entity.Settings;
import com.odel.registration.entity.UnitRule;
import com.odel.registration.repository.SemesterSettingsRepository;
import com.odel.registration.repository.SettingsRepository;
import com.odel.registration.repository.UnitRuleRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Registration rules (A-05 … A-05c): unit limits per level for the university and per programme, the fee block switches, and
 * the semester's course-change deadline. Owned here: academic-service's programmes and grading hold no unit limits.
 * Defaults (never saved): 15 to 20 units and up to 3 electives at every level.
 */
@Service
public class RulesService {
    public static final int DEFAULT_MIN = 15, DEFAULT_MAX = 20, DEFAULT_ELECTIVES = 3;

    private final SettingsRepository settings;
    private final SemesterSettingsRepository semesterSettings;
    private final UnitRuleRepository rules;
    private final AcademicClient academic;
    private final CalendarService calendar;
    private final ActorService actors;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RulesService(SettingsRepository settings, SemesterSettingsRepository semesterSettings, UnitRuleRepository rules, AcademicClient academic,
                        @Lazy CalendarService calendar, ActorService actors, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.settings = settings;
        this.semesterSettings = semesterSettings;
        this.rules = rules;
        this.academic = academic;
        this.calendar = calendar;
        this.actors = actors;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public Settings settings() {
        UUID t = TenantContext.require();
        return tx.execute(x -> settings.findById(t).orElseGet(() -> {
            Settings s = new Settings();
            s.setTenantId(t);
            s.setBlockRegistration(true);
            s.setBlockExams(true);
            s.setPreviewsWhenUnpaid(true);
            return s;
        }));
    }

    /** The limits for a programme at a level: the programme's own, else the university's default, else 15 / 20 / 3. */
    public CourseRules.Limits limits(UUID programmeId, int level) {
        return tx.execute(t -> {
            Optional<UnitRule> own = programmeId == null ? Optional.empty() : rules.forProgramme(programmeId).stream().filter(r -> r.getLevel() == level).findFirst();
            UnitRule r = own.orElseGet(() -> rules.defaults().stream().filter(x -> x.getLevel() == level).findFirst().orElse(null));
            return r == null ? new CourseRules.Limits(DEFAULT_MIN, DEFAULT_MAX, DEFAULT_ELECTIVES) : new CourseRules.Limits(r.getMinUnits(), r.getMaxUnits(), r.getMaxElectives());
        });
    }

    // ---------------------------------------------------------------- A-05

    public Rules get(UUID programmeId, UUID semesterId) {
        CalendarService.Choice c = calendar.pick(semesterId);
        PeerDtos.Reference ref = academic.reference();
        List<PeerDtos.ProgrammeRef> programmes = ref == null || ref.programmes() == null ? List.of() : ref.programmes();
        PeerDtos.ProgrammeRef programme = null;
        if (programmeId != null) programme = programmes.stream().filter(p -> p.id().equals(programmeId)).findFirst().orElseThrow(ApiException::notFound);
        TreeSet<Integer> levels = new TreeSet<>();
        if (programme != null && programme.levels() != null) levels.addAll(programme.levels());
        else programmes.forEach(p -> { if (p.levels() != null) levels.addAll(p.levels()); });
        if (levels.isEmpty()) levels.addAll(List.of(100, 200, 300, 400));

        List<UnitRule> own = programmeId == null ? List.of() : tx.execute(t -> rules.forProgramme(programmeId));
        List<UnitRule> defaults = tx.execute(t -> rules.defaults());
        boolean inherited = programmeId != null && own.isEmpty();
        List<LevelRule> out = new ArrayList<>();
        for (int level : levels) {
            CourseRules.Limits l = programmeId == null
                    ? defaults.stream().filter(r -> r.getLevel() == level).findFirst().map(r -> new CourseRules.Limits(r.getMinUnits(), r.getMaxUnits(), r.getMaxElectives()))
                    .orElse(new CourseRules.Limits(DEFAULT_MIN, DEFAULT_MAX, DEFAULT_ELECTIVES))
                    : limits(programmeId, level);
            Integer compulsory = null;
            if (programmeId != null && c.semester() != null) compulsory = compulsoryUnits(programmeId, level, c.semester().number());
            out.add(new LevelRule(level, l.minUnits(), l.maxUnits(), l.maxElectives(), compulsory, compulsory == null ? null : compulsory <= l.maxUnits()));
        }
        Settings s = settings();
        Set<UUID> withOwn = new HashSet<>(tx.execute(t -> rules.programmesWithOwnRules()));
        List<ProgrammeBrief> ownList = programmes.stream().filter(p -> withOwn.contains(p.id())).map(p -> new ProgrammeBrief(p.id(), p.name())).toList();
        Window w = c.window();
        boolean defaultChanges = c.semester() == null || tx.execute(t -> semesterSettings.forSemester(TenantContext.require(), c.semester().id())
                .map(x -> x.getChangesCloseOn() == null).orElse(true));
        return new Rules(CalendarService.view(c.semester()), new RulesWindow(w.opensOn(), w.closesOn(), w.lateClosesOn(), w.changesCloseOn(), defaultChanges),
                new FeeRules(s.isBlockRegistration(), s.isBlockExams(), s.isPreviewsWhenUnpaid()),
                programme == null ? null : new ProgrammeBrief(programme.id(), programme.name()), inherited, out, ownList,
                "Carry-over courses always count towards the maximum.");
    }

    private int compulsoryUnits(UUID programmeId, int level, int semester) {
        return academic.offerings(programmeId, level, semester).stream().filter(x -> "COMPULSORY".equals(x.kind())).mapToInt(PeerDtos.CourseInfo::units).sum();
    }

    public Rules put(CurrentUser cu, RulesUpdate in) {
        AuditService.Actor actor = actors.actor(cu);
        CalendarService.Choice c = calendar.pick(in.semesterId());
        Map<String, String> f = new LinkedHashMap<>();
        List<LevelRule> levels = in.levels() == null ? List.of() : in.levels();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < levels.size(); i++) {
            LevelRule r = levels.get(i);
            String k = "levels[" + i + "]";
            if (r.level() < 100 || r.level() > 900 || r.level() % 100 != 0) { f.put(k + ".level", "Levels are 100 to 900."); continue; }
            if (!seen.add(r.level())) { f.put(k + ".level", r.level() + "L appears twice."); continue; }
            if (r.minUnits() == null || r.minUnits() < 0 || r.minUnits() > 60) f.put(k + ".minUnits", "The minimum is 0 to 60 units.");
            if (r.maxUnits() == null || r.maxUnits() < 1 || r.maxUnits() > 60) f.put(k + ".maxUnits", "The maximum is 1 to 60 units.");
            if (r.maxElectives() == null || r.maxElectives() < 0 || r.maxElectives() > 20) f.put(k + ".maxElectives", "Electives are 0 to 20.");
            if (r.minUnits() != null && r.maxUnits() != null && r.minUnits() > r.maxUnits()) {
                f.put(k + ".minUnits", "The minimum (" + r.minUnits() + ") is above the maximum (" + r.maxUnits() + ").");
            }
            if (in.programmeId() != null && c.semester() != null && r.maxUnits() != null && !f.containsKey(k + ".maxUnits")) {
                int comp = compulsoryUnits(in.programmeId(), r.level(), c.semester().number());
                if (comp > r.maxUnits()) f.put(k + ".maxUnits", r.level() + "L compulsory courses already come to " + comp + " units, over the maximum of " + r.maxUnits() + ".");
            }
        }
        Window w = c.window();
        if (in.changesCloseOn() != null && w.closesOn() != null) {
            java.time.LocalDate last = w.lateClosesOn() != null ? w.lateClosesOn() : w.closesOn();
            if (in.changesCloseOn().isBefore(last)) f.put("changesCloseOn", "Course changes must stay open until registration closes (" + Dates.day(last) + ").");
        }
        if (in.programmeId() != null) {
            PeerDtos.Reference ref = academic.reference();
            if (ref == null || ref.programmes() == null || ref.programmes().stream().noneMatch(p -> p.id().equals(in.programmeId()))) throw ApiException.notFound();
        }
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);

        UUID tenant = TenantContext.require();
        Instant now = clock.instant();
        tx.executeWithoutResult(t -> {
            if (in.fee() != null) {
                Settings s = settings.findById(tenant).orElseGet(() -> {
                    Settings n = new Settings();
                    n.setTenantId(tenant);
                    n.setBlockRegistration(true);
                    n.setBlockExams(true);
                    n.setPreviewsWhenUnpaid(true);
                    return n;
                });
                if (in.fee().blockRegistration() != null) s.setBlockRegistration(in.fee().blockRegistration());
                if (in.fee().blockExams() != null) s.setBlockExams(in.fee().blockExams());
                if (in.fee().previewsWhenUnpaid() != null) s.setPreviewsWhenUnpaid(in.fee().previewsWhenUnpaid());
                s.setUpdatedAt(now);
                s.setUpdatedByName(actor.name());
                settings.save(s);
            }
            if (in.changesCloseOn() != null && c.semester() != null) {
                SemesterSettings ss = semesterSettings.forSemester(tenant, c.semester().id()).orElseGet(() -> {
                    SemesterSettings n = new SemesterSettings();
                    n.setTenantId(tenant);
                    n.setSemesterId(c.semester().id());
                    return n;
                });
                ss.setChangesCloseOn(in.changesCloseOn());
                ss.setUpdatedAt(now);
                semesterSettings.save(ss);
            }
            List<UnitRule> existing = in.programmeId() == null ? rules.defaults() : rules.forProgramme(in.programmeId());
            for (LevelRule r : levels) {
                UnitRule u = existing.stream().filter(x -> x.getLevel() == r.level()).findFirst().orElseGet(() -> {
                    UnitRule n = new UnitRule();
                    n.setId(UUID.randomUUID());
                    n.setProgrammeId(in.programmeId());
                    n.setLevel(r.level());
                    return n;
                });
                u.setMinUnits(r.minUnits());
                u.setMaxUnits(r.maxUnits());
                u.setMaxElectives(r.maxElectives());
                u.setUpdatedAt(now);
                u.setUpdatedByName(actor.name());
                rules.save(u);
            }
            audit.record(new AuditService.Entry(tenant, actor, "REGISTRATION_RULES_UPDATED", "registration_rules", in.programmeId(),
                    actor.name() + " updated the registration rules" + (in.programmeId() == null ? "" : " of a programme"), null,
                    Map.of("levels", levels.size(), "changesCloseOn", String.valueOf(in.changesCloseOn()), "fee", String.valueOf(in.fee()))));
        });
        return get(in.programmeId(), in.semesterId());
    }

    public void deleteProgramme(CurrentUser cu, UUID programmeId) {
        AuditService.Actor actor = actors.actor(cu);
        tx.executeWithoutResult(t -> {
            rules.deleteForProgramme(programmeId);
            audit.record(new AuditService.Entry(TenantContext.require(), actor, "REGISTRATION_RULES_UPDATED", "registration_rules", programmeId,
                    actor.name() + " set a programme back to the default unit limits", null, null));
        });
    }
}
