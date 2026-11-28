package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.PeerClient;
import com.odel.content.client.VideoGateway;
import com.odel.content.config.ContentProperties;
import com.odel.content.dto.ContentDtos.*;
import com.odel.content.dto.PeerDtos.RegCourse;
import com.odel.content.dto.PeerDtos.SemesterItem;
import com.odel.content.dto.PeerDtos.StudentRef;
import com.odel.content.dto.PeerDtos.TaughtCourse;
import com.odel.content.entity.*;
import com.odel.content.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** The student's side (L-03 … L-09b, E-10, E-11, E-16): registered courses only, open weeks only, signed links, progress. */
@Service
public class StudentContentService {
    static final Duration PLAY_LINK = Duration.ofHours(4);
    static final Duration FILE_LINK = Duration.ofMinutes(30);
    private final Directory directory;
    private final AccessService access;
    private final PeerClient peers;
    private final OfferingRepository offerings;
    private final WeekRepository weeks;
    private final ItemRepository items;
    private final ProgressRepository progress;
    private final AnnouncementService announcements;
    private final VideoGateway videos;
    private final FileStore store;
    private final ContentProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StudentContentService(Directory directory, AccessService access, PeerClient peers, OfferingRepository offerings, WeekRepository weeks,
                                 ItemRepository items, ProgressRepository progress, AnnouncementService announcements, VideoGateway videos,
                                 FileStore store, ContentProperties props, TransactionTemplate tx, Clock clock) {
        this.directory = directory;
        this.access = access;
        this.peers = peers;
        this.offerings = offerings;
        this.weeks = weeks;
        this.items = items;
        this.progress = progress;
        this.announcements = announcements;
        this.videos = videos;
        this.store = store;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    record Ctx(StudentRef student, Offering offering) {}

    Ctx open(CurrentUser cu, UUID offeringId) {
        StudentRef s = access.student(cu);
        Offering o = directory.offering(offeringId);
        access.requireRegistered(s, o);
        return new Ctx(s, o);
    }

    PersonRef lecturer(Offering o) {
        UUID id = directory.lecturerOf(o);
        return Views.person(id, peers.name(id));
    }

    // ---- L-03

    public StudentCourses courses(CurrentUser cu, UUID semesterId) {
        StudentRef s = access.student(cu);
        SemesterItem sem = directory.pick(semesterId);
        if (sem == null) return new StudentCourses(null, false, List.of(), null, "Your university has not set up this session yet. Check back later.", null);
        SemesterRef semRef = new SemesterRef(sem.id(), sem.sessionId(), sem.sessionName(), sem.number(), sem.label());
        Map<UUID, RegCourse> reg = access.registered(s, sem.id());
        if (reg.isEmpty()) {
            return new StudentCourses(semRef, false, List.of(), null, "You have not registered your courses for " + sem.label()
                    + " yet. Register first and your courses appear here.", "/registration");
        }
        List<StudentCourseCard> cards = new ArrayList<>();
        int units = 0;
        LocalDate today = directory.today();
        for (RegCourse rc : reg.values()) {
            units += rc.units();
            TaughtCourse t = directory.taught(sem.sessionId(), rc.courseId());
            var ref = t != null ? t.course() : new com.odel.content.dto.PeerDtos.CourseRef(rc.courseId(), rc.code(), rc.title(), rc.units(), 0, sem.number());
            Offering o = directory.ensure(ref, sem);
            List<Week> ws = weeks.forOffering(o.getId());
            List<Item> is = items.forOffering(o.getId());
            ProgressCalc.State st = ProgressCalc.compute(ws, is, progress.forStudent(o.getId(), s.id()));
            Week current = currentWeek(ws, today);
            UUID lect = t == null ? null : t.lecturerId();
            cards.add(new StudentCourseCard(o.getId(), Views.course(o), Views.person(lect, peers.name(lect)), st.progress(),
                    current == null ? null : new WeekRef(current.getId(), current.getNumber(), current.getTitle()), ws.stream().noneMatch(w -> !w.isHidden())));
        }
        return new StudentCourses(semRef, true, cards, cards.size() + (cards.size() == 1 ? " registered course · " : " registered courses · ") + units
                + (units == 1 ? " unit" : " units"), null, null);
    }

    /** The latest open week. */
    static Week currentWeek(List<Week> ws, LocalDate today) {
        Week cur = null;
        for (Week w : ws) if (w.openOn(today) && (cur == null || w.getOpensOn().isAfter(cur.getOpensOn()) || (w.getOpensOn().equals(cur.getOpensOn()) && w.getNumber() > cur.getNumber()))) cur = w;
        return cur;
    }

    // ---- L-04, E-11

    public StudentOffering offering(CurrentUser cu, UUID id) {
        Ctx c = open(cu, id);
        Offering o = c.offering();
        LocalDate today = directory.today();
        List<Week> ws = weeks.forOffering(o.getId());
        List<Item> is = items.forOffering(o.getId());
        ProgressCalc.State st = ProgressCalc.compute(ws, is, progress.forStudent(o.getId(), c.student().id()));
        Week current = currentWeek(ws, today);
        List<StudentWeekRow> rows = new ArrayList<>();
        for (Week w : ws) {
            if (w.isHidden()) continue;
            ProgressCalc.WeekState ws2 = st.weeks().get(w.getId());
            boolean open = w.openOn(today);
            rows.add(new StudentWeekRow(w.getId(), w.getNumber(), w.getTitle(), open, w.getOpensOn(),
                    open ? null : w.getOpensOn() == null ? "Opening date not set yet" : "Opens " + Labels.day(w.getOpensOn()),
                    ws2 == null ? 0 : ws2.done(), ws2 == null ? 0 : ws2.total(), ws2 != null && ws2.complete(), current != null && current.getId().equals(w.getId())));
        }
        PersonRef lect = lecturer(o);
        boolean empty = rows.isEmpty();
        String lectName = lect == null ? "Your lecturer" : lect.fullName();
        return new StudentOffering(o.getId(), Views.course(o), Views.semester(o), lect,
                new About(o.getOutline(), o.objectiveList(), "Coursework 30% · Exam 70%"), st.progress(), rows,
                announcements.latest(o, 3), continueIn(c.student(), List.of(o.getId())), empty,
                empty ? lectName + " has not added anything to this course yet. You will get a notification when the first week is ready." : null);
    }

    // ---- L-05, E-16

    Week openWeek(Week w) {
        LocalDate today = directory.today();
        if (w.isHidden()) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "This week is not available. Go back to the course page.");
        if (!w.openOn(today)) {
            String label = w.getOpensOn() == null ? null : "Opens " + Labels.day(w.getOpensOn());
            String msg = w.getOpensOn() == null ? "Week " + w.getNumber() + " is not open yet. Your lecturer will set the date."
                    : "Week " + w.getNumber() + " opens " + Labels.day(w.getOpensOn()) + ". Come back then.";
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("opensOn", w.getOpensOn() == null ? null : w.getOpensOn().toString());
            extra.put("openLabel", label);
            throw new ApiException(HttpStatus.CONFLICT, "week_not_open", msg, null, extra);
        }
        return w;
    }

    public StudentWeek week(CurrentUser cu, UUID weekId) {
        Week w = weeks.findInTenant(weekId).orElseThrow(ApiException::notFound);
        Ctx c = open(cu, w.getOfferingId());
        openWeek(w);
        Offering o = c.offering();
        List<Item> list = items.forWeek(w.getId()).stream().filter(i -> !i.isHidden()).toList();
        Map<UUID, ItemProgress> mine = new HashMap<>();
        for (ItemProgress p : progress.forStudent(o.getId(), c.student().id())) mine.put(p.getItemId(), p);
        List<StudentItem> views = list.stream().map(i -> Views.student(i, mine.get(i.getId()))).toList();
        int total = (int) list.stream().filter(Item::usable).count();
        int done = (int) list.stream().filter(i -> i.usable() && mine.containsKey(i.getId()) && mine.get(i.getId()).isDone()).count();
        PersonRef lect = lecturer(o);
        boolean empty = list.isEmpty();
        return new StudentWeek(w.getId(), w.getNumber(), w.getTitle(), w.getNote(), lect, w.getOpensOn(), done, total, done + " of " + total + " done",
                views, empty, empty ? "Nothing here yet. " + (lect == null ? "Your lecturer" : lect.fullName()) + " has not added this week's materials." : null);
    }

    // ---- items

    record ItemCtx(StudentRef student, Offering offering, Week week, Item item) {}

    ItemCtx item(CurrentUser cu, UUID itemId) {
        Item i = items.findInTenant(itemId).orElseThrow(ApiException::notFound);
        if (i.getReplacesItemId() != null || i.isHidden()) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "This item is not available. Go back to the week.");
        Ctx c = open(cu, i.getOfferingId());
        Week w = weeks.findInTenant(i.getWeekId()).orElseThrow(ApiException::notFound);
        openWeek(w);
        return new ItemCtx(c.student(), c.offering(), w, i);
    }

    public StudentItemDetail detail(CurrentUser cu, UUID itemId) {
        ItemCtx c = item(cu, itemId);
        Item i = c.item();
        ItemProgress p = progress.find(i.getId(), c.student().id()).orElse(null);
        List<Item> inWeek = items.forWeek(c.week().getId()).stream().filter(x -> !x.isHidden()).toList();
        NextRef next = null;
        for (int k = 0; k < inWeek.size() - 1; k++) {
            if (inWeek.get(k).getId().equals(i.getId())) {
                Item n = inWeek.get(k + 1);
                next = new NextRef(n.getId(), n.getTitle(), n.getKind().name());
            }
        }
        FileLink file = null;
        Playback playback = null;
        NotReady notReady = null;
        String body = null, warning = null;
        if (i.isFile() && i.usable()) {
            file = new FileLink(store.downloadLink(i.getStorageKey(), FILE_LINK), i.getContentType(), i.getSizeBytes(), Labels.size(i.getSizeBytes()),
                    i.getStatus() == Item.Status.KEPT_ORIGINAL ? i.getFileName() : i.getTitle() + ".pdf", i.isConverted());
            if (i.getKind() == Item.Kind.AUDIO) file = new FileLink(file.url(), i.getContentType(), i.getSizeBytes(), file.sizeLabel(), i.getFileName(), false);
        } else if (i.getKind() == Item.Kind.VIDEO) {
            if (i.usable()) playback = playback(i, p);
            else notReady = notReady(i, c.offering());
        } else if (i.isFile()) {
            notReady = new NotReady("file_preparing", "This file is being prepared. Check back in a few minutes.");
        }
        if (i.getKind() == Item.Kind.PAGE) body = i.getBody();
        if (i.getKind() == Item.Kind.LINK) warning = i.isWarnData() ? "This leaves the portal and uses your mobile data." : "This opens another website.";
        return new StudentItemDetail(Views.student(i, p), new WeekRef(c.week().getId(), c.week().getNumber(), c.week().getTitle()), next, file, body,
                warning, playback, notReady);
    }

    NotReady notReady(Item i, Offering o) {
        String who = i.getCreatedByName() == null ? "Your lecturer" : i.getCreatedByName();
        if (i.getStatus() == Item.Status.FAILED) {
            return new NotReady("video_failed", "This lecture could not be prepared. " + who + " has been told and will upload it again.");
        }
        long min = i.getUploadedAt() == null ? 0 : Duration.between(i.getUploadedAt(), clock.instant()).toMinutes();
        String when = i.getUploadedAt() == null ? who + " is still uploading it" : who + " uploaded it " + (min < 1 ? "just now" : min + (min == 1 ? " minute ago" : " minutes ago"));
        long left = Math.max(5, 20 - min);
        return new NotReady("video_preparing", "Being prepared. " + when + "; check back in about " + left + " minutes.");
    }

    Playback playback(Item i, ItemProgress p) {
        Instant exp = clock.instant().plus(PLAY_LINK);
        Map<String, Long> sizes = i.qualityMap();
        List<String> qs = new ArrayList<>(sizes.keySet());
        VideoGateway.Links links = videos.links(i.getVideoId(), qs, exp);
        int len = i.getDurationSeconds() == null ? 0 : i.getDurationSeconds();
        String def = qs.contains("360p") ? "360p" : qs.isEmpty() ? null : qs.get(0);
        List<Quality> quals = qs.stream().map(q -> new Quality(q, q.equals(def), sizes.get(q), Labels.size(sizes.get(q)), Labels.perMinute(sizes.get(q), len),
                links.hls().get(q), links.mp4().get(q))).toList();
        AudioOption audio;
        if (i.getAudioStatus() == Item.AudioStatus.READY && i.getAudioKey() != null) {
            long a = i.getAudioSizeBytes() == null ? Views.AUDIO_BYTES_PER_SECOND * len : i.getAudioSizeBytes();
            Long ref = sizes.getOrDefault("360p", Views.estimate("360p", len));
            int saving = ref == null || ref == 0 ? 80 : (int) Math.round((1 - (double) a / ref) * 100);
            audio = new AudioOption("AUDIO_FILE", store.downloadLink(i.getAudioKey(), PLAY_LINK), a, Labels.size(a), Labels.perMinute(a, len),
                    "saves about " + (saving / 5 * 5) + "% of the data");
        } else {
            String low = qs.isEmpty() ? null : qs.get(0);
            Long a = low == null ? null : sizes.get(low);
            audio = low == null ? null : new AudioOption("VIDEO_LOW", links.hls().get(low), a, Labels.size(a), Labels.perMinute(a == null ? 0 : a, len),
                    "plays the " + low + " stream without the picture");
        }
        int resume = p == null || p.isDone() ? 0 : p.getPositionSeconds();
        return new Playback(i.getId(), i.getVideoId(), len, def, resume, exp, new Auto(links.master()), quals, audio, List.of(1.0, 1.25, 1.5, 2.0),
                videos.embedUrl(i.getVideoId(), exp));
    }

    public Playback playback(CurrentUser cu, UUID itemId) {
        ItemCtx c = item(cu, itemId);
        Item i = c.item();
        if (i.getKind() != Item.Kind.VIDEO) throw ApiException.badRequest("not_a_video", "This item is not a video. Open it from the week instead.");
        if (!i.usable()) {
            NotReady n = notReady(i, c.offering());
            throw new ApiException(HttpStatus.CONFLICT, n.code(), n.message());
        }
        return playback(i, progress.find(i.getId(), c.student().id()).orElse(null));
    }

    // ---- progress

    ItemProgress row(ItemCtx c) {
        return progress.find(c.item().getId(), c.student().id()).orElseGet(() -> {
            ItemProgress p = new ItemProgress();
            p.setId(UUID.randomUUID());
            p.setOfferingId(c.offering().getId());
            p.setItemId(c.item().getId());
            p.setStudentId(c.student().id());
            p.setUserId(c.student().userId());
            p.setLastSeenAt(clock.instant());
            return p;
        });
    }

    public ProgressResult beat(CurrentUser cu, UUID itemId, ProgressIn in) {
        ItemCtx c = item(cu, itemId);
        Item i = c.item();
        if (i.getKind() != Item.Kind.VIDEO && i.getKind() != Item.Kind.AUDIO) throw ApiException.badRequest("not_playable", "Only videos and audio keep a position.");
        if (!i.usable()) throw new ApiException(HttpStatus.CONFLICT, "video_preparing", "This lecture is not ready yet.");
        return tx.execute(s -> {
            ItemProgress p = row(c);
            Instant now = clock.instant();
            int len = i.getDurationSeconds() == null ? Integer.MAX_VALUE : i.getDurationSeconds();
            int pos = in == null || in.positionSeconds() == null ? p.getPositionSeconds() : Math.max(0, Math.min(len, in.positionSeconds()));
            p.setPositionSeconds(pos);
            if (p.getOpenedAt() == null) p.setOpenedAt(now);
            if (in != null && in.fromSeconds() != null && in.toSeconds() != null && in.toSeconds() > in.fromSeconds()) {
                p.setWatchedSeconds(p.getWatchedSeconds() + Coverage.watched(in.fromSeconds(), in.toSeconds()));
                if (i.getKind() == Item.Kind.VIDEO && i.getDurationSeconds() != null) {
                    p.setBuckets(Coverage.mark(p.getBuckets(), i.getDurationSeconds(), in.fromSeconds(), in.toSeconds()));
                    p.setPercentWatched(Coverage.percent(p.getBuckets()));
                    if (!p.isDone() && Coverage.done(p.getBuckets())) {
                        p.setDone(true);
                        p.setDoneAt(now);
                    }
                }
            }
            p.setLastSeenAt(now);
            progress.save(p);
            return new ProgressResult(p.isDone(), p.getPercentWatched(), p.getWatchedSeconds());
        });
    }

    /** Opening a file, audio or link counts as done; a video or page only records the opening. */
    public Done opened(CurrentUser cu, UUID itemId) {
        ItemCtx c = item(cu, itemId);
        Item i = c.item();
        if (!i.usable()) throw new ApiException(HttpStatus.CONFLICT, "not_ready", "This item is not ready yet. Check back in a few minutes.");
        return tx.execute(s -> {
            ItemProgress p = row(c);
            Instant now = clock.instant();
            if (p.getOpenedAt() == null) p.setOpenedAt(now);
            if (!p.isDone() && (i.isFile() || i.getKind() == Item.Kind.LINK)) {
                p.setDone(true);
                p.setDoneAt(now);
            }
            p.setLastSeenAt(now);
            progress.save(p);
            return new Done(p.isDone());
        });
    }

    public Done markDone(CurrentUser cu, UUID itemId) {
        ItemCtx c = item(cu, itemId);
        Item i = c.item();
        if (i.getKind() == Item.Kind.VIDEO) throw ApiException.badRequest("watch_it", "A video is marked done when you have watched it.");
        return tx.execute(s -> {
            ItemProgress p = row(c);
            Instant now = clock.instant();
            if (p.getOpenedAt() == null) p.setOpenedAt(now);
            if (!p.isDone()) {
                p.setDone(true);
                p.setDoneAt(now);
            }
            p.setLastSeenAt(now);
            progress.save(p);
            return new Done(true);
        });
    }

    // ---- continue (Home)

    Continue continueIn(StudentRef s, Collection<UUID> offeringIds) {
        if (offeringIds.isEmpty()) return null;
        for (ItemProgress p : progress.unfinished(s.id(), offeringIds)) {
            Item i = items.findInTenant(p.getItemId()).orElse(null);
            if (i == null || i.isHidden() || !i.usable() || (i.getKind() != Item.Kind.VIDEO && i.getKind() != Item.Kind.AUDIO)) continue;
            Offering o = offerings.findInTenant(i.getOfferingId()).orElse(null);
            Week w = weeks.findInTenant(i.getWeekId()).orElse(null);
            if (o == null || w == null || !w.openOn(directory.today())) continue;
            String label = i.getDurationSeconds() == null ? Labels.clock(p.getPositionSeconds())
                    : Math.max(1, p.getPositionSeconds() / 60) + " of " + Math.max(1, Math.round(i.getDurationSeconds() / 60f)) + " min";
            return new Continue(i.getId(), o.getId(), o.getCourseCode(), i.getTitle(), i.getKind().name(), p.getPositionSeconds(), i.getDurationSeconds(),
                    w.getNumber(), label);
        }
        return null;
    }

    public Continue continueWatching(CurrentUser cu) {
        StudentRef s = access.student(cu);
        SemesterItem sem = directory.pick(null);
        if (sem == null) return null;
        Map<UUID, RegCourse> reg = access.registered(s, sem.id());
        List<UUID> ids = offerings.forSemester(sem.id()).stream().filter(o -> reg.containsKey(o.getCourseId())).map(Offering::getId).toList();
        return continueIn(s, ids);
    }

    public AnnouncementList announcements(CurrentUser cu, UUID offeringId) {
        Ctx c = open(cu, offeringId);
        return new AnnouncementList(announcements.latest(c.offering(), 200));
    }

    /** For other services: a student's progress per course in a semester. */
    public StudentSummary summary(UUID studentId, UUID semesterId) {
        SemesterItem sem = directory.pick(semesterId);
        if (sem == null) return new StudentSummary(List.of());
        List<Offering> os = offerings.forSemester(sem.id());
        List<CourseSummary> out = new ArrayList<>();
        for (Offering o : os) {
            List<ItemProgress> mine = progress.forStudent(o.getId(), studentId);
            if (mine.isEmpty()) continue;
            ProgressCalc.State st = ProgressCalc.compute(weeks.forOffering(o.getId()), items.forOffering(o.getId()), mine);
            out.add(new CourseSummary(o.getCourseId(), o.getId(), st.percent(), st.weeksDone(), st.weeksTotal()));
        }
        return new StudentSummary(out);
    }
}
