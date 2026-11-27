package com.odel.academic.service;

import com.odel.academic.dto.GradingDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.academic.client.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Grading rules (W-17 … W-17e), versioned: every save is a new row with an effective session, so results computed later
 * (B6) can use the version in force for their session. The built-in default applies until the admin saves the rules.
 */
@Service
public class GradingService {
    private final GradingRuleRepository rules;
    private final ProgrammeRepository programmes;
    private final SessionRepository sessionRepo;
    private final SessionService sessions;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public GradingService(GradingRuleRepository rules, ProgrammeRepository programmes, SessionRepository sessionRepo,
                          @org.springframework.context.annotation.Lazy SessionService sessions, AuditService audit, ObjectMapper json, Clock clock) {
        this.rules = rules;
        this.programmes = programmes;
        this.sessionRepo = sessionRepo;
        this.sessions = sessions;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RulesView get(UUID programmeId) {
        long own = programmesWithOwnRules();
        Optional<GradingRule> def = rules.defaultVersions().stream().findFirst();
        if (programmeId == null) return view(def.orElse(null), null, false, own);
        Programme p = programmes.findById(programmeId).orElseThrow(ApiException::notFound);
        Optional<GradingRule> mine = rules.programmeVersions(programmeId).stream().findFirst().filter(r -> !r.isInherit());
        return mine.map(r -> view(r, p, false, own)).orElseGet(() -> view(def.orElse(null), p, true, own));
    }

    /** True once the admin has saved (reviewed) the default rules. */
    @Transactional(readOnly = true)
    public boolean defaultSaved() {
        return !rules.defaultVersions().isEmpty();
    }

    @Transactional(readOnly = true)
    public long programmesWithOwnRules() {
        Map<UUID, GradingRule> latest = new HashMap<>();
        for (GradingRule r : rules.allProgrammeVersions()) latest.putIfAbsent(r.getProgrammeId(), r);
        return latest.values().stream().filter(r -> !r.isInherit()).count();
    }

    @Transactional(readOnly = true)
    public Rules defaultRules() {
        return rules.defaultVersions().stream().findFirst().map(this::toRules).orElse(Grading.defaults());
    }

    /** For the programme panel on W-12: "Default 5-point scale" or "Own rules · 5-point scale". */
    @Transactional(readOnly = true)
    public String labelFor(UUID programmeId) {
        Optional<GradingRule> mine = rules.programmeVersions(programmeId).stream().findFirst().filter(r -> !r.isInherit());
        if (mine.isPresent()) return "Own rules · " + mine.get().getScale() + "-point scale";
        return "Default " + defaultRules().scale() + "-point scale";
    }

    @Transactional
    public RulesView save(UUID programmeId, RulesIn in, AuditService.Actor actor) {
        Programme p = programmeId == null ? null : programmes.findById(programmeId).orElseThrow(ApiException::notFound);
        Rules r = in.rules();
        Map<String, String> f = Grading.check(r);
        if (!f.isEmpty()) {
            String first = f.values().iterator().next();
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", first, f);
        }
        GradingRule row = newVersion(programmeId, in.effectiveSessionId(), actor);
        apply(row, r);
        rules.saveAndFlush(row);
        String who = p == null ? "the default grading rules" : "grading rules for " + p.getName();
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "GRADING_RULES_SAVED", "grading_rules", row.getId(),
                "Saved " + who + " (version " + row.getVersion() + ": " + r.caWeight() + "% CA / " + r.examWeight() + "% exam, pass mark " + r.passMark() + ")",
                null, Map.of("caWeight", r.caWeight(), "examWeight", r.examWeight(), "passMark", r.passMark(), "version", row.getVersion())));
        return get(programmeId);
    }

    /** The programme follows the default again (kept as a version, so the history shows when). */
    @Transactional
    public void removeOverride(UUID programmeId, AuditService.Actor actor) {
        Programme p = programmes.findById(programmeId).orElseThrow(ApiException::notFound);
        Optional<GradingRule> latest = rules.programmeVersions(programmeId).stream().findFirst();
        if (latest.isEmpty() || latest.get().isInherit()) return;
        GradingRule row = newVersion(programmeId, null, actor);
        apply(row, defaultRules());
        row.setInherit(true);
        rules.saveAndFlush(row);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "GRADING_RULES_SAVED", "grading_rules", row.getId(),
                p.getName() + " now follows the default grading rules", null, null));
    }

    @Transactional(readOnly = true)
    public List<HistoryRow> history(UUID programmeId) {
        if (programmeId != null) programmes.findById(programmeId).orElseThrow(ApiException::notFound);
        List<GradingRule> list = programmeId == null ? rules.defaultVersions() : rules.programmeVersions(programmeId);
        return list.stream().map(r -> new HistoryRow(r.getVersion(), sessionRef(r.getEffectiveSessionId()), r.getCreatedAt(),
                Map.of("fullName", r.getCreatedByName() == null ? "" : r.getCreatedByName()), r.isInherit(), r.getCaWeight(), r.getExamWeight(), r.getPassMark(),
                r.isInherit() ? "Follows the default" : Grading.summary(toRules(r), programmeId != null))).toList();
    }

    private GradingRule newVersion(UUID programmeId, UUID effectiveSessionId, AuditService.Actor actor) {
        List<GradingRule> versions = programmeId == null ? rules.defaultVersions() : rules.programmeVersions(programmeId);
        UUID effective = effectiveSessionId;
        if (effective != null) sessionRepo.findById(effective).orElseThrow(() -> ApiException.fields(HttpStatus.BAD_REQUEST, "invalid",
                "Choose a session of this university.", Map.of("effectiveSessionId", "Choose a session of this university.")));
        else effective = sessions.current().map(AcademicSession::getId).orElse(null);
        GradingRule row = new GradingRule();
        row.setId(UUID.randomUUID());
        row.setProgrammeId(programmeId);
        row.setVersion(versions.isEmpty() ? 1 : versions.get(0).getVersion() + 1);
        row.setEffectiveSessionId(effective);
        row.setCreatedAt(clock.instant());
        row.setCreatedBy(actor.userId());
        row.setCreatedByName(actor.name());
        return row;
    }

    private void apply(GradingRule row, Rules r) {
        row.setCaWeight(r.caWeight());
        row.setExamWeight(r.examWeight());
        row.setPassMark(r.passMark());
        row.setScale(r.scale());
        row.setRoundTotals(Boolean.TRUE.equals(r.roundTotals()));
        try {
            row.setBands(json.writeValueAsString(r.bands().stream()
                    .map(b -> new Band(b.grade().trim().toUpperCase(), b.from(), b.to(), b.point(), b.remark() == null ? "" : b.remark().trim()))
                    .sorted(Comparator.comparing(Band::from).reversed()).toList()));
            row.setStanding(json.writeValueAsString(r.standing()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Rules toRules(GradingRule r) {
        try {
            return new Rules(r.getCaWeight(), r.getExamWeight(), r.getPassMark(), r.getScale(), r.isRoundTotals(),
                    json.readValue(r.getBands(), new TypeReference<List<Band>>() {}), json.readValue(r.getStanding(), Standing.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SessionRef sessionRef(UUID id) {
        return id == null ? null : sessionRepo.findById(id).map(s -> new SessionRef(s.getId(), s.getName())).orElse(null);
    }

    private RulesView view(GradingRule row, Programme p, boolean inherited, long own) {
        Rules r = row == null ? Grading.defaults() : toRules(row);
        SessionRef eff = row == null ? sessions.current().map(s -> new SessionRef(s.getId(), s.getName())).orElse(null) : sessionRef(row.getEffectiveSessionId());
        boolean programmeRules = p != null && !inherited;
        return new RulesView(p == null ? "DEFAULT" : "PROGRAMME", p == null ? null : new ProgrammeRef(p.getId(), p.getName()),
                row == null ? 0 : row.getVersion(), row != null, eff, r.caWeight(), r.examWeight(), r.passMark(), r.scale(),
                Boolean.TRUE.equals(r.roundTotals()), r.bands(), r.standing(), Grading.summary(r, programmeRules), own, inherited, Grading.example(r));
    }
}
