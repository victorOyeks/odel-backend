package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.PeerClient;
import com.odel.content.dto.ContentDtos.*;
import com.odel.content.dto.PeerDtos;
import com.odel.content.dto.PeerDtos.SemesterItem;
import com.odel.content.dto.PeerDtos.TaughtCourse;
import com.odel.content.entity.Item;
import com.odel.content.entity.Offering;
import com.odel.content.entity.Week;
import com.odel.content.repository.ItemRepository;
import com.odel.content.repository.OfferingRepository;
import com.odel.content.repository.WeekRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** My courses (T-01), the course overview and settings (T-02, T-08), copying last session's course (T-06) and dating its weeks. */
@Service
public class StaffCourseService {
    private final Directory directory;
    private final AccessService access;
    private final PeerClient peers;
    private final OfferingRepository offerings;
    private final WeekRepository weeks;
    private final ItemRepository items;
    private final AuditService audit;
    private final Clock clock;

    public StaffCourseService(Directory directory, AccessService access, PeerClient peers, OfferingRepository offerings, WeekRepository weeks,
                              ItemRepository items, AuditService audit, Clock clock) {
        this.directory = directory;
        this.access = access;
        this.peers = peers;
        this.offerings = offerings;
        this.weeks = weeks;
        this.items = items;
        this.audit = audit;
        this.clock = clock;
    }

    static boolean readsAll(Role r) {
        return r == Role.ODEL_ADMIN || r == Role.ODEL_DIRECTOR || r == Role.MANAGEMENT || r == Role.CENTRE_COORDINATOR;
    }

    public CourseList list(CurrentUser cu, UUID semesterId) {
        access.requireStaff(cu);
        SemesterItem sem = directory.pick(semesterId);
        if (sem == null) return new CourseList(null, List.of(), List.of(), "No session is set up yet. The ODeL office sets up the session first.");
        PeerDtos.Teaching t = peers.teaching(sem.sessionId(), readsAll(cu.activeRole()) ? null : cu.userId());
        List<TaughtCourse> taught = t == null || t.items() == null ? List.of() : t.items().stream().filter(x -> x.course().semester() == sem.number()).toList();
        List<CourseCard> cards = new ArrayList<>();
        int students = 0;
        List<Offering> os = new ArrayList<>();
        for (TaughtCourse x : taught) os.add(directory.ensure(x.course(), sem));
        Map<UUID, List<Week>> weeksBy = os.isEmpty() ? Map.of() : weeks.forOfferings(os.stream().map(Offering::getId).toList()).stream().collect(Collectors.groupingBy(Week::getOfferingId));
        Map<UUID, List<Item>> itemsBy = os.isEmpty() ? Map.of() : items.forOfferings(os.stream().map(Offering::getId).toList()).stream()
                .collect(Collectors.groupingBy(Item::getOfferingId));
        for (int k = 0; k < taught.size(); k++) {
            TaughtCourse x = taught.get(k);
            Offering o = os.get(k);
            boolean lecturer = cu.userId().equals(x.lecturerId());
            String myRole = cu.activeRole() == Role.ODEL_ADMIN ? "ODEL_ADMIN" : lecturer ? "LECTURER"
                    : x.facilitatorIds() != null && x.facilitatorIds().contains(cu.userId()) ? "FACILITATOR" : cu.activeRole().name();
            boolean canEdit = cu.activeRole() == Role.ODEL_ADMIN || (lecturer && cu.activeRole() == Role.LECTURER);
            int n = classSize(o);
            students += n;
            List<Week> ws = weeksBy.getOrDefault(o.getId(), List.of());
            int ready = readyWeeks(ws, itemsBy.getOrDefault(o.getId(), List.of()));
            cards.add(new CourseCard(o.getId(), Views.course(o), Views.person(x.lecturerId(), peers.name(x.lecturerId())), myRole, canEdit, n, ws.size(),
                    ready, ready + " of " + ws.size() + " weeks ready", ws.isEmpty()));
        }
        List<SemesterOption> sems = directory.sessionSemesters(sem).stream().map(s -> new SemesterOption(s.id(), s.label())).toList();
        String subtitle = cards.size() + (cards.size() == 1 ? " course · " : " courses · ") + students + (students == 1 ? " student" : " students");
        return new CourseList(new SemesterRef(sem.id(), sem.sessionId(), sem.sessionName(), sem.number(), sem.label()), sems, cards, subtitle);
    }

    int classSize(Offering o) {
        try {
            return peers.classList(o.getCourseId(), o.getSemesterId()).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Weeks with at least one item ready for students. */
    static int readyWeeks(List<Week> ws, List<Item> is) {
        Set<UUID> withReady = is.stream().filter(i -> !i.isHidden() && i.usable()).map(Item::getWeekId).collect(Collectors.toSet());
        return (int) ws.stream().filter(w -> withReady.contains(w.getId())).count();
    }

    @Transactional(readOnly = true)
    public Overview overview(CurrentUser cu, UUID id) {
        Offering o = directory.offering(id);
        AccessService.Level level = access.level(cu, o);
        return overview(cu, o, level);
    }

    Overview overview(CurrentUser cu, Offering o, AccessService.Level level) {
        List<Week> ws = weeks.forOffering(o.getId());
        List<Item> is = items.forOffering(o.getId());
        int ready = readyWeeks(ws, is);
        int pct = ws.isEmpty() ? 0 : (int) Math.round(ready * 100.0 / ws.size());
        UUID lecturer = directory.lecturerOf(o);
        CopiedFrom from = o.getCopiedFromId() == null ? null : offerings.findInTenant(o.getCopiedFromId())
                .map(f -> new CopiedFrom(f.getId(), f.semesterLabel())).orElse(null);
        boolean canCopy = ws.isEmpty() && !sources(o).isEmpty();
        String myRole = cu.activeRole() == Role.ODEL_ADMIN ? "ODEL_ADMIN" : cu.userId().equals(lecturer) ? "LECTURER" : cu.activeRole().name();
        return new Overview(o.getId(), Views.course(o), Views.semester(o), Views.person(lecturer, peers.name(lecturer)), level == AccessService.Level.EDIT,
                myRole, o.getOutline(), o.objectiveList(), "Coursework 30% · Exam 70% · set by the ODeL office", ws.size(), ready,
                (int) is.stream().filter(i -> !i.isHidden()).count(), classSize(o), ws.isEmpty(), pct,
                ready + " of " + ws.size() + " weeks ready · " + pct + "%", from, canCopy);
    }

    @Transactional
    public Overview settings(CurrentUser cu, UUID id, SettingsIn in) {
        Offering o = directory.offering(id);
        access.edit(cu, o);
        Map<String, String> f = new LinkedHashMap<>();
        String outline = in.outline() == null ? null : in.outline().trim();
        if (outline != null && outline.length() > 600) f.put("outline", "Keep the outline to 600 characters (it is " + outline.length() + ").");
        List<String> objectives = in.objectives() == null ? List.of() : in.objectives().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (objectives.size() > 8) f.put("objectives", "Up to 8 objectives. Merge some of them.");
        for (String s : objectives) if (s.length() > 200 || s.contains("\n")) f.put("objectives", "Each objective is one line of up to 200 characters.");
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        o.setOutline(outline == null || outline.isEmpty() ? null : outline);
        o.setObjectives(objectives.isEmpty() ? null : String.join("\n", objectives));
        o.setUpdatedAt(clock.instant());
        audit.record(new AuditService.Entry(TenantContext.require(), access.actor(cu), "CONTENT_COURSE_UPDATED", "CONTENT", o.getId(),
                o.getCourseCode() + " outline and objectives updated (" + o.semesterLabel() + ")", null, null));
        return overview(cu, o, AccessService.Level.EDIT);
    }

    // ---- copy (T-06)

    List<Offering> sources(Offering o) {
        return offerings.forCourse(o.getCourseId()).stream()
                .filter(x -> !x.getId().equals(o.getId()) && !x.getSessionId().equals(o.getSessionId()) && x.getSessionName().compareTo(o.getSessionName()) < 0)
                .filter(x -> !weeks.forOffering(x.getId()).isEmpty()).toList();
    }

    @Transactional(readOnly = true)
    public CopySources copySources(CurrentUser cu, UUID id) {
        Offering o = directory.offering(id);
        access.level(cu, o);
        return new CopySources(sources(o).stream().map(x -> {
            UUID lect = directory.lecturerOf(x);
            return new CopySource(x.getId(), x.semesterLabel(), weeks.forOffering(x.getId()).size(), items.forOffering(x.getId()).size(), peers.name(lect));
        }).toList());
    }

    @Transactional
    public CopyResult copy(CurrentUser cu, UUID id, CopyIn in) {
        Offering o = directory.offering(id);
        access.edit(cu, o);
        if (!weeks.forOffering(o.getId()).isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "not_empty", "This course already has weeks. Copy only into an empty course.");
        List<Offering> src = sources(o);
        Offering from = in == null || in.fromOfferingId() == null ? src.stream().findFirst().orElse(null)
                : src.stream().filter(x -> x.getId().equals(in.fromOfferingId())).findFirst().orElse(null);
        if (from == null) throw new ApiException(HttpStatus.NOT_FOUND, "no_source", "There is no earlier session of " + o.getCourseCode() + " with content to copy.");
        var now = clock.instant();
        if (o.getOutline() == null) o.setOutline(from.getOutline());
        if (o.getObjectives() == null) o.setObjectives(from.getObjectives());
        o.setCopiedFromId(from.getId());
        o.setUpdatedAt(now);
        Map<UUID, Week> newWeek = new HashMap<>();
        for (Week w : weeks.forOffering(from.getId())) {
            Week c = new Week();
            c.setId(UUID.randomUUID());
            c.setOfferingId(o.getId());
            c.setNumber(w.getNumber());
            c.setTitle(w.getTitle());
            c.setNote(w.getNote());
            c.setHidden(w.isHidden());
            c.setOpensOn(null);
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            weeks.save(c);
            newWeek.put(w.getId(), c);
        }
        List<Skipped> skipped = new ArrayList<>();
        int copied = 0;
        for (Item i : items.forOffering(from.getId())) {
            Week target = newWeek.get(i.getWeekId());
            if (target == null) continue;
            if (!i.usable()) {
                skipped.add(new Skipped(i.getTitle(), i.getStatus() == Item.Status.FAILED ? "it had failed" : "it was not ready yet"));
                continue;
            }
            Item c = ItemCopier.copy(i);
            c.setId(UUID.randomUUID());
            c.setOfferingId(o.getId());
            c.setWeekId(target.getId());
            c.setCreatedBy(cu.userId());
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            items.save(c);
            copied++;
        }
        String msg = "Course copied · " + newWeek.size() + (newWeek.size() == 1 ? " week" : " weeks") + " into " + o.getCourseCode();
        audit.record(new AuditService.Entry(TenantContext.require(), access.actor(cu), "CONTENT_COURSE_COPIED", "CONTENT", o.getId(),
                o.getCourseCode() + ": copied " + newWeek.size() + " weeks and " + copied + " items from " + from.semesterLabel() + " into " + o.semesterLabel(), null, null));
        return new CopyResult(newWeek.size(), copied, skipped, msg);
    }

    @Transactional
    public DatesResult dates(CurrentUser cu, UUID id, DatesIn in) {
        Offering o = directory.offering(id);
        access.edit(cu, o);
        if (in == null || in.firstOpensOn() == null) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Choose when week 1 opens.",
                Map.of("firstOpensOn", "Choose when week 1 opens."));
        int every = in.everyDays() == null ? 7 : in.everyDays();
        if (every < 1 || every > 31) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", "Weeks open between 1 and 31 days apart.",
                Map.of("everyDays", "Weeks open between 1 and 31 days apart."));
        boolean onlyEmpty = in.onlyEmpty() == null || in.onlyEmpty();
        int updated = 0;
        List<Week> ws = weeks.forOffering(o.getId());
        for (int k = 0; k < ws.size(); k++) {
            Week w = ws.get(k);
            if (onlyEmpty && w.getOpensOn() != null) continue;
            w.setOpensOn(in.firstOpensOn().plusDays((long) k * every));
            w.setUpdatedAt(clock.instant());
            updated++;
        }
        audit.record(new AuditService.Entry(TenantContext.require(), access.actor(cu), "CONTENT_WEEK_CHANGED", "CONTENT", o.getId(),
                o.getCourseCode() + ": " + updated + " weeks dated from " + Labels.day(in.firstOpensOn()), null, null));
        return new DatesResult(updated, updated + (updated == 1 ? " week" : " weeks") + " dated, from " + Labels.day(in.firstOpensOn()));
    }

    public LocalDate today() {
        return directory.today();
    }
}
