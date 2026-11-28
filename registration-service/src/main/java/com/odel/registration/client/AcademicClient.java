package com.odel.registration.client;

import com.odel.common.internal.InternalClient;
import com.odel.registration.dto.PeerDtos.*;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** registration-service → academic-service: the calendar, what is offered, courses, coordinators, programmes. */
@Component
public class AcademicClient {
    private static final InternalClient.Service S = InternalClient.Service.ACADEMIC;
    private final InternalClient internal;

    public AcademicClient(InternalClient internal) {
        this.internal = internal;
    }

    public Calendar calendar() {
        return internal.get(S, "/internal/academic/calendar", Calendar.class);
    }

    public SemesterInfo semester(UUID id) {
        return internal.get(S, "/internal/academic/semesters/" + id, SemesterInfo.class);
    }

    public List<CourseInfo> offerings(UUID programmeId, int level, int semester) {
        Courses c = internal.get(S, "/internal/academic/offerings?programmeId=" + programmeId + "&level=" + level + "&semester=" + semester, Courses.class);
        return c == null || c.items() == null ? List.of() : c.items();
    }

    public List<CourseInfo> courses(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Courses c = internal.get(S, "/internal/academic/courses?ids=" + ids.stream().map(UUID::toString).collect(Collectors.joining(",")), Courses.class);
        return c == null || c.items() == null ? List.of() : c.items();
    }

    public List<CourseInfo> courseByCode(String code) {
        Courses c = internal.get(S, "/internal/academic/courses?code=" + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8), Courses.class);
        return c == null || c.items() == null ? List.of() : c.items();
    }

    public Coordinators coordinators() {
        return internal.get(S, "/internal/academic/coordinators", Coordinators.class);
    }

    public Reference reference() {
        return internal.get(S, "/internal/academic/reference", Reference.class);
    }
}
