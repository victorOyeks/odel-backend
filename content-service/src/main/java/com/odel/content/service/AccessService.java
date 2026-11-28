package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.content.client.PeerClient;
import com.odel.content.dto.PeerDtos.*;
import com.odel.content.entity.Offering;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Who may do what. Students: only courses they are registered for in that semester. Staff: the course's lecturer and the ODeL admin
 * edit; facilitators, the director, management and coordinators read (coordinators see only their centres' students).
 */
@Service
public class AccessService {
    public enum Level { EDIT, READ }

    static final Set<Role> STAFF = EnumSet.of(Role.ODEL_ADMIN, Role.ODEL_DIRECTOR, Role.MANAGEMENT, Role.CENTRE_COORDINATOR, Role.LECTURER, Role.FACILITATOR);
    private final PeerClient peers;
    private final Directory directory;

    public AccessService(PeerClient peers, Directory directory) {
        this.peers = peers;
        this.directory = directory;
    }

    public static boolean isStaff(CurrentUser cu) {
        return cu.activeRole() != null && STAFF.contains(cu.activeRole());
    }

    public void requireStaff(CurrentUser cu) {
        if (!isStaff(cu)) throw ApiException.forbidden();
    }

    /** Edit or read for a staff member on an offering; 403 when they have nothing to do with it. */
    public Level level(CurrentUser cu, Offering o) {
        requireStaff(cu);
        Role r = cu.activeRole();
        if (r == Role.ODEL_ADMIN) return Level.EDIT;
        if (r == Role.ODEL_DIRECTOR || r == Role.MANAGEMENT || r == Role.CENTRE_COORDINATOR) return Level.READ;
        TaughtCourse t = directory.taught(o.getSessionId(), o.getCourseId());
        if (t != null && cu.userId().equals(t.lecturerId())) return r == Role.LECTURER ? Level.EDIT : Level.READ;
        if (t != null && t.facilitatorIds() != null && t.facilitatorIds().contains(cu.userId())) return Level.READ;
        throw new ApiException(HttpStatus.FORBIDDEN, "not_your_course", "You do not teach " + o.getCourseCode() + " this session. Ask the ODeL office if that is wrong.");
    }

    public Level edit(CurrentUser cu, Offering o) {
        Level l = level(cu, o);
        if (l != Level.EDIT) throw new ApiException(HttpStatus.FORBIDDEN, "read_only",
                "You can view this course but not change it. Ask the course lecturer or the ODeL office.");
        return l;
    }

    /** The signed-in student's record. */
    public StudentRef student(CurrentUser cu) {
        if (cu.activeRole() != Role.STUDENT) throw ApiException.forbidden();
        try {
            return peers.studentByUser(cu.userId());
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) throw new ApiException(HttpStatus.FORBIDDEN, "no_student_record",
                    "We could not find your student record. Contact your study centre.");
            throw e;
        }
    }

    /** The courses the student is registered for in a semester (course id → course). */
    public Map<UUID, RegCourse> registered(StudentRef s, UUID semesterId) {
        StudentCourses sc = peers.registered(s.id(), semesterId);
        Map<UUID, RegCourse> m = new LinkedHashMap<>();
        if (sc != null && sc.registered() && sc.courses() != null) for (RegCourse c : sc.courses()) m.put(c.courseId(), c);
        return m;
    }

    public void requireRegistered(StudentRef s, Offering o) {
        if (!registered(s, o.getSemesterId()).containsKey(o.getCourseId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_registered", "You are not registered for " + o.getCourseCode()
                    + " this semester. Register the course first, or ask your study centre.");
        }
    }

    /** The centres a coordinator coordinates in the offering's session (empty for everyone else = no limit). */
    public Optional<Set<UUID>> centreLimit(CurrentUser cu, Offering o) {
        if (cu.activeRole() != Role.CENTRE_COORDINATOR) return Optional.empty();
        Coordinators c = peers.coordinators(o.getSessionId());
        Set<UUID> mine = new HashSet<>();
        if (c != null && c.items() != null) for (CentreCoordinator x : c.items()) if (cu.userId().equals(x.userId())) mine.add(x.centreId());
        return Optional.of(mine);
    }

    public AuditService.Actor actor(CurrentUser cu) {
        String name = peers.name(cu.userId());
        return new AuditService.Actor(cu.userId(), cu.tenantId(), name == null ? "Unknown" : name, cu.activeRole() == null ? null : cu.activeRole().name());
    }
}
