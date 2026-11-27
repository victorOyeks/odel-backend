package com.odel.academic.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Requests and responses: Grading rules (W-17 …). */
public final class GradingDtos {
    private GradingDtos() {}

    public record SessionRef(UUID id, String name) {}

    public record ProgrammeRef(UUID id, String name) {}

    public record RulesView(String scope, ProgrammeRef programme, int version, boolean saved, SessionRef effectiveSession,
                            int caWeight, int examWeight, int passMark, int scale, boolean roundTotals, List<Band> bands,
                            Standing standing, String summary, long programmesWithOwnRules, boolean inheritedFromDefault, String example) {}

    public record RulesIn(Integer caWeight, Integer examWeight, Integer passMark, Integer scale, Boolean roundTotals,
                          List<Band> bands, Standing standing, UUID effectiveSessionId) {
        public Rules rules() {
            return new Rules(caWeight, examWeight, passMark, scale, roundTotals == null ? Boolean.TRUE : roundTotals, bands, standing);
        }
    }

    public record HistoryRow(int version, SessionRef effectiveSession, Instant savedAt, Map<String, String> savedBy, boolean followsDefault,
                             int caWeight, int examWeight, int passMark, String summary) {}

    public record Band(String grade, Integer from, Integer to, Integer point, String remark) {}

    public record DegreeClass(String name, BigDecimal from) {}

    public record Standing(BigDecimal probationBelow, Integer withdrawAfterProbationSemesters, Boolean carryOverBeforeGraduating,
                           List<DegreeClass> classes) {}

    public record Rules(Integer caWeight, Integer examWeight, Integer passMark, Integer scale, Boolean roundTotals,
                        List<Band> bands, Standing standing) {}
}
