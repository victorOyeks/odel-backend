package com.odel.payment.client;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.internal.InternalClient;
import com.odel.common.internal.InternalClient.Service;
import com.odel.common.tenancy.TenantContext;
import com.odel.payment.dto.PeerDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** payment-service → academic, student, tenant, identity and notification services (/internal/**, the shared key). */
@Component
public class PeerClient {
    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);
    private final InternalClient internal;
    private final OdelProperties props;
    private final RestClient http = RestClient.create();
    private final Map<UUID, Timed<TenantView>> tenants = new ConcurrentHashMap<>();
    private final Map<String, Timed<byte[]>> crests = new ConcurrentHashMap<>();
    private final Map<UUID, Timed<String>> names = new ConcurrentHashMap<>();

    record Timed<T>(T value, long at) {
        boolean fresh(long ms) { return System.currentTimeMillis() - at < ms; }
    }

    public PeerClient(InternalClient internal, OdelProperties props) {
        this.internal = internal;
        this.props = props;
    }

    /** Sessions' semesters of the current university, and which one is current. */
    public Semesters semesters() {
        return internal.get(Service.ACADEMIC, "/internal/academic/semesters", Semesters.class);
    }

    public Student studentByUser(UUID userId) {
        return internal.get(Service.STUDENTS, "/internal/students/directory/by-user/" + userId, Student.class);
    }

    public Student student(UUID studentId) {
        return internal.get(Service.STUDENTS, "/internal/students/directory/" + studentId, Student.class);
    }

    public List<Student> students() {
        Directory d = internal.get(Service.STUDENTS, "/internal/students/directory", Directory.class);
        return d == null || d.items() == null ? List.of() : d.items();
    }

    /** The university (name, colour, portal address, crest path), cached for a minute. */
    public TenantView tenant(UUID tenantId) {
        Timed<TenantView> t = tenants.get(tenantId);
        if (t != null && t.fresh(60_000)) return t.value();
        TenantView v = TenantContext.runAs(tenantId, () -> internal.get(Service.TENANT, "/internal/tenants/" + tenantId, TenantView.class));
        tenants.put(tenantId, new Timed<>(v, System.currentTimeMillis()));
        return v;
    }

    public TenantView tenant() {
        return tenant(TenantContext.require());
    }

    public List<TenantView> universities() {
        List<TenantView> all = internal.get(Service.TENANT, "/internal/tenants", new ParameterizedTypeReference<List<TenantView>>() {});
        return all == null ? List.of() : all;
    }

    public TenantLook look() {
        TenantView t = tenant();
        return new TenantLook(t.name(), t.shortName(), t.primaryColor() == null ? "#1A2368" : t.primaryColor());
    }

    /** The crest as PNG bytes for the receipt, or null (no logo, an SVG, or tenant-service unreachable). */
    public byte[] crestPng(TenantView t) {
        String path = t.logoUrl();
        if (path == null || !path.toLowerCase().endsWith(".png")) return null;
        Timed<byte[]> c = crests.get(path);
        if (c != null) return c.value();
        try {
            byte[] bytes = http.get().uri(props.services().tenant() + path).retrieve().body(byte[].class);
            crests.put(path, new Timed<>(bytes, System.currentTimeMillis()));
            return bytes;
        } catch (Exception e) {
            log.info("Crest not loaded for {}: {}", t.slug(), e.getMessage());
            return null;
        }
    }

    /** A staff member's or the provider's full name (audit and "updated by"), cached for five minutes. */
    public String personName(UUID userId) {
        Timed<String> n = names.get(userId);
        if (n != null && n.fresh(300_000)) return n.value();
        String name;
        try {
            StaffView s = internal.get(Service.IDENTITY, "/internal/users/" + userId, StaffView.class);
            name = s == null ? "Someone" : s.fullName();
        } catch (ApiException e) {
            name = "Someone";
        }
        names.put(userId, new Timed<>(name, System.currentTimeMillis()));
        return name;
    }

    /** Sends an email (and bell) through notification-service. A failure is logged, never thrown: the payment itself stands. */
    public void send(Message m) {
        try {
            internal.post(Service.NOTIFICATION, "/internal/messages", m, Map.class);
        } catch (Exception e) {
            log.warn("Message {} not sent: {}", m.templateKey(), e.getMessage());
        }
    }
}
