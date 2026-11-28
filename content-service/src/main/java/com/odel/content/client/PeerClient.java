package com.odel.content.client;

import com.odel.common.internal.InternalClient;
import com.odel.common.internal.InternalClient.Service;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.config.ContentProperties;
import com.odel.content.dto.PeerDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * content-service → the other services: semesters and teaching staff (academic), class lists and who is registered (registration),
 * students (student), names (identity), the university (tenant) and messages (notification). Short caches per university.
 */
@Component
public class PeerClient {
    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);
    private final InternalClient internal;
    private final ContentProperties props;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    record Cached(Object value, long at) {}

    public PeerClient(InternalClient internal, ContentProperties props) {
        this.internal = internal;
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, long ms, Supplier<T> load) {
        String k = TenantContext.current() + "|" + key;
        Cached c = cache.get(k);
        if (c != null && System.currentTimeMillis() - c.at() < ms) return (T) c.value();
        T v = load.get();
        cache.put(k, new Cached(v, System.currentTimeMillis()));
        if (cache.size() > 5000) cache.clear();
        return v;
    }

    /** Forget cached answers about a student's registration (tests and dev). */
    public void forget() {
        cache.clear();
    }

    // ---- academic
    public Semesters semesters() {
        return cached("semesters", 30_000, () -> internal.get(Service.ACADEMIC, "/internal/academic/semesters", Semesters.class));
    }

    public Teaching teaching(UUID sessionId, UUID userId) {
        String q = "?sessionId=" + sessionId + (userId == null ? "" : "&userId=" + userId);
        return cached("teaching" + q, 20_000, () -> internal.get(Service.ACADEMIC, "/internal/academic/teaching" + q, Teaching.class));
    }

    public Coordinators coordinators(UUID sessionId) {
        return cached("coordinators" + sessionId, 20_000,
                () -> internal.get(Service.ACADEMIC, "/internal/academic/coordinators?sessionId=" + sessionId, Coordinators.class));
    }

    // ---- registration
    public StudentCourses registered(UUID studentId, UUID semesterId) {
        return cached("reg" + studentId + semesterId, 15_000, () -> internal.getAt(props.registrationUrl(),
                "/internal/registration/students/" + studentId + "/courses?semesterId=" + semesterId, StudentCourses.class));
    }

    public List<CourseStudent> classList(UUID courseId, UUID semesterId) {
        CourseStudents c = cached("class" + courseId + semesterId, 15_000, () -> internal.getAt(props.registrationUrl(),
                "/internal/registration/courses/" + courseId + "/students?semesterId=" + semesterId, CourseStudents.class));
        return c == null || c.items() == null ? List.of() : c.items();
    }

    // ---- student
    public StudentRef studentByUser(UUID userId) {
        return cached("student" + userId, 60_000, () -> internal.get(Service.STUDENTS, "/internal/students/by-user/" + userId, StudentRef.class));
    }

    public List<StudentRef> roster() {
        Roster r = cached("roster", 30_000, () -> internal.get(Service.STUDENTS, "/internal/students/roster", Roster.class));
        return r == null || r.items() == null ? List.of() : r.items();
    }

    // ---- identity
    public String name(UUID userId) {
        if (userId == null) return null;
        try {
            StaffView s = cached("user" + userId, 300_000, () -> internal.get(Service.IDENTITY, "/internal/users/" + userId, StaffView.class));
            return s == null ? null : s.fullName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public StaffView user(UUID userId) {
        return cached("user" + userId, 300_000, () -> internal.get(Service.IDENTITY, "/internal/users/" + userId, StaffView.class));
    }

    // ---- tenant
    public TenantView tenant() {
        return cached("tenant", 60_000, () -> internal.get(Service.TENANT, "/internal/tenants/" + TenantContext.require(), TenantView.class));
    }

    public TenantLook look() {
        TenantView t = tenant();
        return new TenantLook(t.name(), t.shortName(), t.primaryColor() == null ? "#1A2368" : t.primaryColor());
    }

    public String portal() {
        String p = tenant().portalUrl();
        return p == null ? "" : p.replaceAll("/+$", "");
    }

    // ---- notification (a failed hand-over is logged, never fatal)
    public void send(Message m) {
        try {
            internal.post(Service.NOTIFICATION, "/internal/messages", m, Map.class);
        } catch (RuntimeException e) {
            log.warn("message {} to {} not queued: {}", m.templateKey(), m.email(), e.getMessage());
        }
    }

    public boolean sendAll(List<Message> messages) {
        if (messages.isEmpty()) return true;
        try {
            for (int i = 0; i < messages.size(); i += 500) {
                internal.post(Service.NOTIFICATION, "/internal/messages/bulk", new BulkRequest(messages.subList(i, Math.min(messages.size(), i + 500))), Map.class);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("{} messages not queued: {}", messages.size(), e.getMessage());
            return false;
        }
    }
}
