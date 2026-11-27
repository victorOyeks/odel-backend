package com.odel.student.repository;

import com.odel.student.dto.WelcomeDtos.DeliveryView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** The welcome template per university and the per-student deliveries of each send (JDBC: thousands of rows at a time). */
@Repository
public class WelcomeRepository {
    private final JdbcTemplate jdbc;

    public WelcomeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Template(String subject, String body, Instant updatedAt, String updatedByName) {}

    public Optional<Template> template(UUID tenantId) {
        return jdbc.query("select subject, body, updated_at, updated_by_name from welcome_templates where tenant_id = ?",
                (rs, i) -> new Template(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4)), tenantId).stream().findFirst();
    }

    public void saveTemplate(UUID tenantId, String subject, String body, UUID by, String byName, Instant now) {
        jdbc.update("""
                insert into welcome_templates (tenant_id, subject, body, updated_at, updated_by, updated_by_name) values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set subject = excluded.subject, body = excluded.body, updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by, updated_by_name = excluded.updated_by_name""",
                tenantId, subject, body, Timestamp.from(now), by, byName);
    }

    public void deleteTemplate(UUID tenantId) {
        jdbc.update("delete from welcome_templates where tenant_id = ?", tenantId);
    }

    /** One delivery per student not yet active: PENDING when there is an email, else skipped. Returns how many were added. */
    public int addAllNotActivated(UUID tenantId, UUID sendId, Instant now) {
        return jdbc.update("""
                insert into welcome_deliveries (id, tenant_id, send_id, student_id, email, status, reason, updated_at)
                select gen_random_uuid(), tenant_id, ?, id, email,
                       case when email is null then 'SKIPPED_NO_EMAIL' else 'PENDING' end,
                       case when email is null then 'No email on record' end, ?
                from students where tenant_id = ? and status in ('IMPORTED', 'INVITED')""", sendId, Timestamp.from(now), tenantId);
    }

    /** The reminder: students of the first send who still have not activated and have an email. */
    public int addReminders(UUID tenantId, UUID reminderSendId, UUID originalSendId, Instant now) {
        return jdbc.update("""
                insert into welcome_deliveries (id, tenant_id, send_id, student_id, email, status, updated_at)
                select gen_random_uuid(), s.tenant_id, ?, s.id, s.email, 'PENDING', ?
                from students s where s.tenant_id = ? and s.status in ('IMPORTED', 'INVITED') and s.email is not null
                  and exists (select 1 from welcome_deliveries d where d.send_id = ? and d.student_id = s.id)""",
                reminderSendId, Timestamp.from(now), tenantId, originalSendId);
    }

    public void addOne(UUID tenantId, UUID sendId, UUID studentId, String email, Instant now) {
        jdbc.update("""
                insert into welcome_deliveries (id, tenant_id, send_id, student_id, email, status, reason, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), tenantId, sendId, studentId, email,
                email == null ? "SKIPPED_NO_EMAIL" : "PENDING", email == null ? "No email on record" : null, Timestamp.from(now));
    }

    public Map<String, Long> counts(UUID tenantId, UUID sendId) {
        Map<String, Long> m = new HashMap<>();
        jdbc.query("select status, count(*) from welcome_deliveries where tenant_id = ? and send_id = ? group by status",
                rs -> { m.put(rs.getString(1), rs.getLong(2)); }, tenantId, sendId);
        return m;
    }

    public List<DeliveryView> deliveries(UUID tenantId, UUID sendId, List<String> statuses, int offset, int limit) {
        return jdbc.query("""
                select d.student_id, s.matric_number, s.first_name || ' ' || s.surname, d.email, d.status, d.reason, d.updated_at
                from welcome_deliveries d join students s on s.id = d.student_id
                where d.tenant_id = ? and d.send_id = ? and (cardinality(?::text[]) = 0 or d.status = any (?::text[]))
                order by s.surname, s.first_name offset ? limit ?""",
                (rs, i) -> new DeliveryView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5).equals("PENDING") || rs.getString(5).equals("QUEUED") ? "SENDING" : rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant()),
                tenantId, sendId, statuses.toArray(new String[0]), statuses.toArray(new String[0]), offset, limit);
    }

    public long countDeliveries(UUID tenantId, UUID sendId, List<String> statuses) {
        Long n = jdbc.queryForObject("""
                select count(*) from welcome_deliveries where tenant_id = ? and send_id = ? and (cardinality(?::text[]) = 0 or status = any (?::text[]))""",
                Long.class, tenantId, sendId, statuses.toArray(new String[0]), statuses.toArray(new String[0]));
        return n == null ? 0 : n;
    }

    // ---- the worker (no Hibernate session here: it runs for every university, so the university is explicit) ----

    public List<com.odel.student.entity.Student> students(UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
                select id, tenant_id, user_id, matric_number, surname, first_name, email, programme_name, level, centre_code, centre_name,
                       entry_session, status from students where tenant_id = ? and id = any (?)""", (rs, i) -> {
            var s = new com.odel.student.entity.Student();
            s.setId(rs.getObject(1, UUID.class));
            s.setTenantId(rs.getObject(2, UUID.class));
            s.setUserId(rs.getObject(3, UUID.class));
            s.setMatricNumber(rs.getString(4));
            s.setSurname(rs.getString(5));
            s.setFirstName(rs.getString(6));
            s.setEmail(rs.getString(7));
            s.setProgrammeName(rs.getString(8));
            s.setLevel(rs.getInt(9));
            s.setCentreCode(rs.getString(10));
            s.setCentreName(rs.getString(11));
            s.setEntrySession(rs.getString(12));
            s.setStatus(com.odel.student.entity.Student.Status.valueOf(rs.getString(13)));
            return s;
        }, tenantId, ids.toArray(new UUID[0]));
    }

    public record SendText(UUID id, String kind, String subject, String body, UUID requestedBy, String requestedByName) {}

    public Map<UUID, SendText> sendTexts(UUID tenantId, Collection<UUID> ids) {
        Map<UUID, SendText> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        jdbc.query("select id, kind, subject, body, requested_by, requested_by_name from welcome_sends where tenant_id = ? and id = any (?)",
                rs -> { m.put(rs.getObject(1, UUID.class), new SendText(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6))); }, tenantId, ids.toArray(new UUID[0]));
        return m;
    }

    /** Starts the reminder of a send: a new REMINDER send to its students still not active. */
    public void startReminder(UUID tenantId, UUID originalSendId, Instant now) {
        SendText o = sendTexts(tenantId, List.of(originalSendId)).get(originalSendId);
        if (o == null) return;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into welcome_sends (id, tenant_id, kind, status, subject, body, parent_send_id, total_students, skipped_active,
                    requested_by, requested_by_name, started_at) values (?, ?, 'REMINDER', 'RUNNING', ?, ?, ?, 0, 0, ?, ?, ?)""",
                id, tenantId, o.subject(), o.body(), originalSendId, o.requestedBy(), o.requestedByName(), Timestamp.from(now));
        int n = addReminders(tenantId, id, originalSendId, now);
        jdbc.update("update welcome_sends set total_students = ? where id = ?", n, id);
        jdbc.update("update welcome_sends set reminder_sent_at = ? where id = ?", Timestamp.from(now), originalSendId);
    }


    public record Open(UUID id, UUID tenantId, UUID sendId, UUID studentId, String email, UUID messageId) {}

    public List<Open> lockPending(int limit) {
        return jdbc.query("""
                select id, tenant_id, send_id, student_id, email, message_id from welcome_deliveries
                where status = 'PENDING' order by updated_at limit ? for update skip locked""",
                (rs, i) -> new Open(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getObject(6, UUID.class)), limit);
    }

    public List<Open> lockQueued(int limit) {
        return jdbc.query("""
                select id, tenant_id, send_id, student_id, email, message_id from welcome_deliveries
                where status = 'QUEUED' order by updated_at limit ? for update skip locked""",
                (rs, i) -> new Open(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getObject(6, UUID.class)), limit);
    }

    public void setStatus(List<Object[]> rows) {
        // status, message_id, reason, updated_at, id
        jdbc.batchUpdate("update welcome_deliveries set status = ?, message_id = coalesce(?, message_id), reason = ?, updated_at = ? where id = ?", rows);
    }

    public int finishSends(Instant now) {
        return jdbc.update("""
                update welcome_sends s set status = 'DONE', finished_at = ? where status = 'RUNNING'
                and not exists (select 1 from welcome_deliveries d where d.send_id = s.id and d.status in ('PENDING', 'QUEUED'))""", Timestamp.from(now));
    }

    public record Due(UUID id, UUID tenantId) {}

    public List<Due> remindersDue(Instant now) {
        return jdbc.query("""
                select id, tenant_id from welcome_sends where kind = 'ALL' and reminder_due_at <= ? and reminder_sent_at is null
                order by reminder_due_at limit 20 for update skip locked""",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), Timestamp.from(now));
    }

    public void markInvited(UUID tenantId, List<UUID> studentIds, Instant now) {
        if (studentIds.isEmpty()) return;
        jdbc.update("""
                insert into student_status_history (id, tenant_id, student_id, from_status, to_status, reason, created_at)
                select gen_random_uuid(), tenant_id, id, 'IMPORTED', 'INVITED', 'Welcome email sent', ? from students
                where tenant_id = ? and id = any (?) and status = 'IMPORTED'""", Timestamp.from(now), tenantId, studentIds.toArray(new UUID[0]));
        jdbc.update("""
                update students set status = case when status = 'IMPORTED' then 'INVITED' else status end, welcome_sent_at = ?, updated_at = ?
                where tenant_id = ? and id = any (?)""", Timestamp.from(now), Timestamp.from(now), tenantId, studentIds.toArray(new UUID[0]));
    }
}
