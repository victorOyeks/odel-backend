package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.PeerClient;
import com.odel.content.dto.ContentDtos.*;
import com.odel.content.dto.PeerDtos.CourseStudent;
import com.odel.content.dto.PeerDtos.Message;
import com.odel.content.dto.PeerDtos.StudentRef;
import com.odel.content.entity.Announcement;
import com.odel.content.entity.Offering;
import com.odel.content.repository.AnnouncementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Course announcements (T-05, T-05b, L-13): to the registered students by bell, push and email (FR-4.5). */
@Service
public class AnnouncementService {
    private final Directory directory;
    private final AccessService access;
    private final PeerClient peers;
    private final AnnouncementRepository announcements;
    private final StaffProgressService classProgress;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AnnouncementService(Directory directory, AccessService access, PeerClient peers, AnnouncementRepository announcements,
                               StaffProgressService classProgress, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.directory = directory;
        this.access = access;
        this.peers = peers;
        this.announcements = announcements;
        this.classProgress = classProgress;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    static String audienceLabel(Announcement a) {
        return switch (a.getAudience()) {
            case ALL -> "All " + a.getRecipients() + (a.getRecipients() == 1 ? " student" : " students");
            case INACTIVE -> a.getRecipients() + " inactive " + (a.getRecipients() == 1 ? "student" : "students") + " (no activity for 14 days)";
            case STUDENTS -> a.getRecipients() == 1 ? "1 student" : a.getRecipients() + " students";
        };
    }

    static AnnouncementView view(Announcement a, String message) {
        return new AnnouncementView(a.getId(), a.getTitle(), a.getBody(), Views.person(a.getAuthorUserId(), a.getAuthorName()), a.getPostedAt(),
                Labels.at(a.getPostedAt()), a.getEditedAt() != null, a.getEditedAt(), a.getAudience().name(), audienceLabel(a), a.getRecipients(),
                a.isEmail(), message);
    }

    public List<AnnouncementView> latest(Offering o, int n) {
        return announcements.forOffering(o.getId()).stream().limit(n).map(a -> view(a, null)).toList();
    }

    public AnnouncementList list(CurrentUser cu, UUID offeringId) {
        Offering o = directory.offering(offeringId);
        access.level(cu, o);
        return new AnnouncementList(latest(o, 500));
    }

    static Map<String, String> check(String title, String body) {
        Map<String, String> f = new LinkedHashMap<>();
        if (title == null || title.isBlank()) f.put("title", "Give the announcement a title.");
        else if (title.trim().length() > 120) f.put("title", "Keep the title to 120 characters.");
        if (body == null || body.isBlank()) f.put("body", "Write the message first.");
        else if (body.length() > 5000) f.put("body", "Keep the message to 5,000 characters.");
        return f;
    }

    public AnnouncementView post(CurrentUser cu, UUID offeringId, AnnouncementIn in) {
        Offering o = directory.offering(offeringId);
        access.edit(cu, o);
        Map<String, String> f = check(in.title(), in.body());
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        Announcement.Audience audience;
        try {
            audience = Announcement.Audience.valueOf(in.audience() == null ? "ALL" : in.audience().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_audience", "Choose who it goes to: all students, inactive students or chosen students.");
        }
        List<CourseStudent> all = peers.classList(o.getCourseId(), o.getSemesterId());
        List<CourseStudent> to = switch (audience) {
            case ALL -> all;
            case INACTIVE -> classProgress.inactive(o);
            case STUDENTS -> {
                Set<UUID> ids = new HashSet<>(in.studentIds() == null ? List.of() : in.studentIds());
                List<CourseStudent> chosen = all.stream().filter(s -> ids.contains(s.studentId())).toList();
                if (chosen.size() != ids.size()) throw ApiException.badRequest("not_in_class", "Some of the chosen students are not registered for " + o.getCourseCode() + ".");
                yield chosen;
            }
        };
        if (to.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "no_students", audience == Announcement.Audience.ALL
                    ? "No students are registered for " + o.getCourseCode() + " yet. Post again once they register."
                    : "No students match. Nobody is inactive right now.");
        }
        boolean email = in.email() == null || in.email();
        Announcement a = new Announcement();
        a.setId(UUID.randomUUID());
        a.setOfferingId(o.getId());
        a.setTitle(in.title().trim());
        a.setBody(in.body().trim());
        a.setAuthorUserId(cu.userId());
        a.setAuthorName(access.actor(cu).name());
        a.setAudience(audience);
        a.setRecipients(to.size());
        a.setEmail(email);
        a.setPostedAt(clock.instant());
        tx.executeWithoutResult(s -> {
            announcements.save(a);
            audit.record(new AuditService.Entry(TenantContext.require(), access.actor(cu), "CONTENT_ANNOUNCEMENT_POSTED", "CONTENT", a.getId(),
                    o.getCourseCode() + ": announcement \"" + a.getTitle() + "\" to " + audienceLabel(a).toLowerCase(Locale.ROOT), null, null));
        });
        Map<UUID, StudentRef> roster = peers.roster().stream().collect(Collectors.toMap(StudentRef::id, Function.identity(), (x, y) -> x));
        String link = "/courses/" + o.getId() + "/announcements/" + a.getId();
        String url = peers.portal() + link;
        var look = peers.look();
        List<Message> messages = new ArrayList<>();
        for (CourseStudent s : to) {
            StudentRef r = roster.get(s.studentId());
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", r != null && r.firstName() != null ? r.firstName() : s.fullName());
            vars.put("courseCode", o.getCourseCode());
            vars.put("courseTitle", o.getCourseTitle());
            vars.put("title", a.getTitle());
            vars.put("body", a.getBody());
            vars.put("author", a.getAuthorName());
            vars.put("announcementUrl", url);
            messages.add(new Message(s.userId(), email && r != null ? r.email() : null, "content.announcement", vars, link, look));
        }
        boolean queued = peers.sendAll(messages);
        return view(a, queued ? "Sent to " + to.size() + (to.size() == 1 ? " student" : " students")
                : "Posted. Notifications could not be queued just now; students still see it on the course page.");
    }

    public AnnouncementView edit(CurrentUser cu, UUID id, AnnouncementIn in) {
        Announcement a = announcements.findInTenant(id).orElseThrow(ApiException::notFound);
        Offering o = directory.offering(a.getOfferingId());
        access.edit(cu, o);
        if (!cu.userId().equals(a.getAuthorUserId()) && cu.activeRole() != Role.ODEL_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_author", "Only " + a.getAuthorName() + " or the ODeL office can change this announcement.");
        }
        Map<String, String> f = check(in.title(), in.body());
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        return tx.execute(s -> {
            Announcement x = announcements.findInTenant(id).orElseThrow();
            x.setTitle(in.title().trim());
            x.setBody(in.body().trim());
            x.setEditedAt(clock.instant());
            audit.record(new AuditService.Entry(TenantContext.require(), access.actor(cu), "CONTENT_ANNOUNCEMENT_EDITED", "CONTENT", x.getId(),
                    o.getCourseCode() + ": announcement \"" + x.getTitle() + "\" edited", null, null));
            return view(x, "Saved. Students see the new text; it is not sent again.");
        });
    }

}
