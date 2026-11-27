package com.odel.student.repository;

import com.odel.student.service.ImportValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Batch writes for an import (3,500 students in a few seconds), with the university set explicitly on every row. */
@Repository
public class StudentBulkRepository {
    private final JdbcTemplate jdbc;

    public StudentBulkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, ImportValidator.Existing> existing(UUID tenantId) {
        Map<String, ImportValidator.Existing> out = new HashMap<>();
        jdbc.query("""
                select id, matric_number, surname, first_name, other_names, email, phone, gender, date_of_birth, programme_code, level,
                       centre_code, entry_session, status from students where tenant_id = ?""", rs -> {
            Date dob = rs.getDate(9);
            out.put(rs.getString(2), new ImportValidator.Existing(rs.getObject(1, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getString(8), dob == null ? null : dob.toLocalDate(), rs.getString(10), rs.getInt(11),
                    rs.getString(12), rs.getString(13), rs.getString(14)));
        }, tenantId);
        return out;
    }

    public record NewStudent(UUID id, ImportValidator.StudentData data) {}

    public void insert(UUID tenantId, UUID importId, List<NewStudent> students, Instant now) {
        Timestamp ts = Timestamp.from(now);
        List<Object[]> rows = new ArrayList<>(students.size());
        for (NewStudent s : students) {
            var d = s.data();
            rows.add(new Object[]{s.id(), tenantId, d.matricNumber(), d.surname(), d.firstName(), d.otherNames(), d.email(), d.phone(), d.gender(),
                    d.dateOfBirth() == null ? null : Date.valueOf(d.dateOfBirth()), d.programme().id(), d.programme().code(), d.programme().name(),
                    d.level(), d.centre().id(), d.centre().code(), d.centre().name(), d.centre().shortName(), d.entrySession(), importId, ts, ts});
        }
        jdbc.batchUpdate("""
                insert into students (id, tenant_id, matric_number, surname, first_name, other_names, email, phone, gender, date_of_birth,
                    programme_id, programme_code, programme_name, level, centre_id, centre_code, centre_name, centre_short_name, entry_session,
                    status, import_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IMPORTED', ?, ?, ?)""", rows);
    }

    public void update(UUID tenantId, Map<UUID, ImportValidator.StudentData> changes, Instant now) {
        Timestamp ts = Timestamp.from(now);
        List<Object[]> rows = new ArrayList<>(changes.size());
        changes.forEach((id, d) -> rows.add(new Object[]{d.surname(), d.firstName(), d.otherNames(), d.email(), d.phone(), d.gender(),
                d.dateOfBirth() == null ? null : Date.valueOf(d.dateOfBirth()), d.programme().id(), d.programme().code(), d.programme().name(),
                d.level(), d.centre().id(), d.centre().code(), d.centre().name(), d.centre().shortName(), ts, id, tenantId}));
        jdbc.batchUpdate("""
                update students set surname = ?, first_name = ?, other_names = ?, email = ?, phone = ?, gender = ?, date_of_birth = ?,
                    programme_id = ?, programme_code = ?, programme_name = ?, level = ?, centre_id = ?, centre_code = ?, centre_name = ?,
                    centre_short_name = ?, updated_at = ? where id = ? and tenant_id = ?""", rows);
    }

    public void linkUsers(UUID tenantId, Map<UUID, UUID> studentToUser) {
        List<Object[]> rows = new ArrayList<>(studentToUser.size());
        studentToUser.forEach((s, u) -> rows.add(new Object[]{u, s, tenantId}));
        jdbc.batchUpdate("update students set user_id = ? where id = ? and tenant_id = ? and user_id is null", rows);
    }

    public record StatusChange(UUID studentId, String from, String to, String reason) {}

    public void history(UUID tenantId, List<StatusChange> changes, UUID actorId, String actorName, Instant now) {
        Timestamp ts = Timestamp.from(now);
        List<Object[]> rows = new ArrayList<>(changes.size());
        for (var c : changes) rows.add(new Object[]{UUID.randomUUID(), tenantId, c.studentId(), c.from(), c.to(), c.reason(), actorId, actorName, ts});
        jdbc.batchUpdate("""
                insert into student_status_history (id, tenant_id, student_id, from_status, to_status, reason, actor_user_id, actor_name, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
    }
}
