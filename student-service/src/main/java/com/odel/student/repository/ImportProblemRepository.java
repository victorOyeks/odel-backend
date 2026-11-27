package com.odel.student.repository;

import com.odel.student.dto.ImportDtos.ProblemView;
import com.odel.student.service.ImportValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The problems an import check found, kept for the report pages and the problem-rows download. */
@Repository
public class ImportProblemRepository {
    private final JdbcTemplate jdbc;

    public ImportProblemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(UUID tenantId, UUID importId, List<ImportValidator.Problem> problems) {
        jdbc.update("delete from import_problems where tenant_id = ? and import_id = ?", tenantId, importId);
        List<Object[]> rows = new ArrayList<>(problems.size());
        for (var p : problems) {
            rows.add(new Object[]{UUID.randomUUID(), tenantId, importId, p.row(), cut(p.matricNumber(), 60), cut(p.name(), 300), p.severity().name(),
                    p.field(), cut(p.message(), 500)});
        }
        jdbc.batchUpdate("""
                insert into import_problems (id, tenant_id, import_id, row_number, matric_number, name, severity, field, message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
    }

    public List<ProblemView> page(UUID tenantId, UUID importId, String severity, int offset, int limit) {
        return jdbc.query("""
                select row_number, matric_number, name, severity, field, message from import_problems
                where tenant_id = ? and import_id = ? and (?::text is null or severity = ?)
                order by case when severity = 'ERROR' then 0 else 1 end, row_number, message offset ? limit ?""",
                (rs, i) -> new ProblemView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                tenantId, importId, severity, severity, offset, limit);
    }

    public long count(UUID tenantId, UUID importId, String severity) {
        Long n = jdbc.queryForObject("select count(*) from import_problems where tenant_id = ? and import_id = ? and (?::text is null or severity = ?)",
                Long.class, tenantId, importId, severity, severity);
        return n == null ? 0 : n;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
