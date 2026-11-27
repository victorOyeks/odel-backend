package com.odel.identity.service;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.PageResponse;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.identity.dto.AuditDtos.Actor;
import com.odel.identity.dto.AuditDtos.Row;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** The university's own audit log (app.audit_log, only its rows): sign-ins, role changes, setup changes, students. */
@Service
public class AuditLogService {
    /** One SQL expression that sorts every action into a type the screen filters by. */
    static final String TYPE_SQL = """
            case
              when a.action in ('SIGN_IN','SIGN_IN_FAILED','SIGN_OUT','MFA_SET_UP','MFA_FAILED','MFA_RESET','ROLE_SWITCHED')
                or a.action like 'STUDENT_SIGN_IN%' or a.action like 'STUDENT_ACTIVATED%' or a.action like 'PASSWORD_%' then 'SIGN_IN'
              when a.action in ('STAFF_INVITED','STAFF_SUSPENDED','STAFF_REINSTATED','ROLE_GRANTED','ROLE_REMOVED','INVITATION_RESENT') then 'ROLES'
              when a.action like 'SESSION_%' or a.action like 'FACULTY_%' or a.action like 'DEPARTMENT_%' or a.action like 'PROGRAMME_%'
                or a.action like 'COURSE_%' or a.action like 'CENTRE_%' or a.action like 'ROOM_%' or a.action like 'STAFFING_%'
                or a.action like 'GRADING_%' or a.action in ('IMPORT_STRUCTURE','IMPORT_CATALOGUE') then 'SETUP'
              when a.action like 'STUDENT%' or a.action like 'WELCOME_%' then 'STUDENTS'
              else 'OTHER'
            end""";
    public static final Set<String> TYPES = Set.of("SIGN_IN", "ROLES", "SETUP", "STUDENTS", "OTHER");

    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public AuditLogService(JdbcTemplate jdbc, OdelProperties props) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(props.timeZone());
    }

    public PageResponse<Row> list(String type, String action, UUID actorId, LocalDate from, LocalDate to, int page, int size) {
        int s = Math.min(Math.max(size, 1), 200), p = Math.max(page, 0);
        if (type != null && !type.isBlank() && !TYPES.contains(type.toUpperCase(Locale.ROOT))) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Choose a type to filter by.", Map.of("type", "Use SIGN_IN, ROLES, SETUP, STUDENTS or OTHER."));
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "The end date is before the start date.", Map.of("to", "Choose a date on or after the start date."));
        }
        StringBuilder where = new StringBuilder("where a.tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(TenantContext.require()));
        if (type != null && !type.isBlank()) { where.append(" and (").append(TYPE_SQL).append(") = ?"); args.add(type.toUpperCase(Locale.ROOT)); }
        if (action != null && !action.isBlank()) { where.append(" and a.action = ?"); args.add(action.trim().toUpperCase(Locale.ROOT)); }
        if (actorId != null) { where.append(" and a.actor_user_id = ?"); args.add(actorId); }
        if (from != null) { where.append(" and a.created_at >= ?"); args.add(Timestamp.from(from.atStartOfDay(zone).toInstant())); }
        if (to != null) { where.append(" and a.created_at < ?"); args.add(Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant())); }
        Long total = jdbc.queryForObject("select count(*) from app.audit_log a " + where, Long.class, args.toArray());
        args.add(s);
        args.add((long) p * s);
        List<Row> items = jdbc.query("select a.id, a.created_at, " + TYPE_SQL + " as type, a.action, a.summary, a.actor_user_id, a.actor_name, a.actor_role, a.ip "
                        + "from app.audit_log a " + where + " order by a.created_at desc limit ? offset ?",
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
                        new Actor(rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), label(rs.getString(8))), rs.getString(9)),
                args.toArray());
        return new PageResponse<>(items, p, s, total == null ? 0 : total);
    }

    static String label(String role) {
        if (role == null) return null;
        try { return Role.valueOf(role).label(); } catch (IllegalArgumentException e) { return role; }
    }
}
