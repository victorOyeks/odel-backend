package com.odel.registration.client;

import com.odel.common.internal.InternalClient;
import com.odel.registration.dto.PeerDtos.*;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** registration-service → student-service: who the student is (programme, level, centre). */
@Component
public class StudentsClient {
    private static final InternalClient.Service S = InternalClient.Service.STUDENTS;
    private final InternalClient internal;

    public StudentsClient(InternalClient internal) {
        this.internal = internal;
    }

    public StudentRef byUser(UUID userId) {
        return internal.get(S, "/internal/students/by-user/" + userId, StudentRef.class);
    }

    public StudentRef byId(UUID id) {
        return internal.get(S, "/internal/students/" + id, StudentRef.class);
    }

    public StudentRef byMatric(String matric) {
        return internal.get(S, "/internal/students/by-matric?matric=" + URLEncoder.encode(matric, StandardCharsets.UTF_8), StudentRef.class);
    }

    public List<StudentRef> roster() {
        Roster r = internal.get(S, "/internal/students/roster", Roster.class);
        return r == null || r.items() == null ? List.of() : r.items();
    }
}
