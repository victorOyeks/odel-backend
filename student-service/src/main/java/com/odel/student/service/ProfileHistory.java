package com.odel.student.service;

import com.odel.student.client.NotificationClient;
import com.odel.student.client.TenantClient;
import com.odel.student.dto.NotificationDtos;
import com.odel.student.entity.ProfileChange;
import com.odel.student.entity.Student;
import com.odel.student.repository.ProfileChangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** The record's change history (W-22b "Changes") and the email + bell that tells the student a contact detail changed. */
@Component
public class ProfileHistory {
    private static final Logger log = LoggerFactory.getLogger(ProfileHistory.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final ProfileChangeRepository changes;
    private final NotificationClient notifications;
    private final TenantClient tenants;
    private final Clock clock;
    private final com.odel.student.client.IdentityClient identity;

    public ProfileHistory(ProfileChangeRepository changes, NotificationClient notifications, TenantClient tenants, Clock clock,
                          com.odel.student.client.IdentityClient identity) {
        this.identity = identity;
        this.changes = changes;
        this.notifications = notifications;
        this.tenants = tenants;
        this.clock = clock;
    }

    /** Call inside the transaction that changes the record. Values longer than the column are cut. */
    public void record(Student s, String field, String oldValue, String newValue, ProfileChange.Source source, UUID requestId, UUID actorId, String actorName) {
        ProfileChange c = new ProfileChange();
        c.setId(UUID.randomUUID());
        c.setStudentId(s.getId());
        c.setField(field);
        c.setOldValue(cut(oldValue));
        c.setNewValue(cut(newValue));
        c.setSource(source);
        c.setRequestId(requestId);
        c.setActorUserId(actorId);
        c.setActorName(actorName);
        c.setCreatedAt(clock.instant());
        changes.save(c);
    }

    /** "Sat 28 Nov at 2:14 pm" (Lagos time). */
    public static String when(Instant at) {
        var t = at.atZone(ZoneId.of("Africa/Lagos"));
        return DAY.format(t) + " at " + TIME.format(t).toLowerCase(Locale.ROOT);
    }

    /** student.contact_changed: what changed (one line each), when and by whom. Never fails the change. */
    public void tellContactChanged(Student s, List<String> lines, boolean byOffice) {
        if (s.getEmail() == null && s.getUserId() == null) return;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("firstName", s.getFirstName());
        v.put("changes", String.join("\n", lines));
        v.put("when", when(clock.instant()));
        v.put("by", byOffice ? "the ODeL office" : "you");
        send(s, "student.contact_changed", v);
    }

    public void send(Student s, String templateKey, Map<String, ?> vars) {
        try {
            notifications.send(new NotificationDtos.Message(s.getUserId(), s.getEmail(), templateKey, vars, "/profile", tenants.look()));
        } catch (RuntimeException e) {
            log.warn("profile notice {} for student {} not queued: {}", templateKey, s.getId(), e.getMessage());
        }
    }

    /** A bell for every ODeL admin who is not suspended: "Adeola Okafor (WU/ODL/24/0153) asked for a correction to the surname". Best effort. */
    public void tellOffice(Student s, String what) {
        List<Map<String, Object>> admins;
        try {
            admins = identity.staffWithRole("ODEL_ADMIN");
        } catch (RuntimeException e) {
            log.warn("ODeL admins not found for the profile request bell: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> a : admins) {
            if (a.get("id") == null || "SUSPENDED".equals(String.valueOf(a.get("status")))) continue;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("studentName", s.displayName());
            v.put("matricNumber", s.getMatricNumber());
            v.put("what", what);
            try {
                notifications.send(new NotificationDtos.Message(UUID.fromString(a.get("id").toString()), null, "staff.profile_request_new", v,
                        "/staff/students/profile-requests", tenants.look()));
            } catch (RuntimeException e) {
                log.warn("profile request bell for {} not queued: {}", a.get("id"), e.getMessage());
            }
        }
    }

    private static String cut(String v) {
        return v == null ? null : v.length() > 400 ? v.substring(0, 400) : v;
    }
}
