package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.security.CurrentUser;
import com.odel.content.client.PeerClient;
import com.odel.content.dto.ContentDtos.*;
import com.odel.content.dto.PeerDtos.CourseStudent;
import com.odel.content.entity.*;
import com.odel.content.repository.ItemRepository;
import com.odel.content.repository.ProgressRepository;
import com.odel.content.repository.WeekRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The lecturer's view of the class (T-07, T-07b): every registered student with percent, weeks done, videos watched, minutes watched,
 * last activity and a status: INACTIVE (no activity for 14 days), NOT_STARTED, BEHIND (fewer weeks done than open weeks with content
 * minus one) or ON_TRACK.
 */
@Service
public class StaffProgressService {
    static final Duration INACTIVE_AFTER = Duration.ofDays(14);
    private final Directory directory;
    private final AccessService access;
    private final PeerClient peers;
    private final WeekRepository weeks;
    private final ItemRepository items;
    private final ProgressRepository progress;
    private final Clock clock;

    public StaffProgressService(Directory directory, AccessService access, PeerClient peers, WeekRepository weeks, ItemRepository items,
                                ProgressRepository progress, Clock clock) {
        this.directory = directory;
        this.access = access;
        this.peers = peers;
        this.weeks = weeks;
        this.items = items;
        this.progress = progress;
        this.clock = clock;
    }

    record Computed(CourseStudent student, ProgressRow row, ProgressCalc.State state, List<ItemProgress> mine) {}

    record Class(Offering offering, List<Week> weeks, List<Item> items, List<Computed> rows, int videos, int openWeeks) {}

    Class compute(CurrentUser cu, UUID offeringId) {
        Offering o = directory.offering(offeringId);
        access.level(cu, o);
        return compute(o, access.centreLimit(cu, o));
    }

    public Class compute(Offering o, Optional<Set<UUID>> centres) {
        List<Week> ws = weeks.forOffering(o.getId());
        List<Item> is = items.forOffering(o.getId());
        Map<UUID, Week> wById = ws.stream().collect(Collectors.toMap(Week::getId, w -> w));
        List<Item> countable = is.stream().filter(i -> ProgressCalc.counts(i, wById)).toList();
        Set<UUID> videoIds = countable.stream().filter(i -> i.getKind() == Item.Kind.VIDEO).map(Item::getId).collect(Collectors.toSet());
        LocalDate today = directory.today();
        Set<UUID> weeksWithContent = countable.stream().map(Item::getWeekId).collect(Collectors.toSet());
        int openWeeks = (int) ws.stream().filter(w -> w.openOn(today) && weeksWithContent.contains(w.getId())).count();
        Map<UUID, List<ItemProgress>> byStudent = progress.forOffering(o.getId()).stream().collect(Collectors.groupingBy(ItemProgress::getStudentId));
        Instant now = clock.instant();
        List<Computed> rows = new ArrayList<>();
        for (CourseStudent s : peers.classList(o.getCourseId(), o.getSemesterId())) {
            if (centres.isPresent() && !centres.get().contains(s.centreId())) continue;
            List<ItemProgress> mine = byStudent.getOrDefault(s.studentId(), List.of());
            ProgressCalc.State st = ProgressCalc.compute(ws, is, mine);
            int videosWatched = (int) mine.stream().filter(p -> p.isDone() && videoIds.contains(p.getItemId())).count();
            int minutes = (int) Math.round(mine.stream().mapToInt(ItemProgress::getWatchedSeconds).sum() / 60.0);
            Instant last = mine.stream().map(ItemProgress::getLastSeenAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            String status = last == null ? "NOT_STARTED" : last.isBefore(now.minus(INACTIVE_AFTER)) ? "INACTIVE"
                    : st.weeksDone() < openWeeks - 1 ? "BEHIND" : "ON_TRACK";
            String label = switch (status) {
                case "NOT_STARTED" -> "Not started";
                case "INACTIVE" -> "Inactive";
                case "BEHIND" -> "Behind";
                default -> "On track";
            };
            ProgressRow row = new ProgressRow(s.studentId(), s.fullName(), s.matricNumber(), new CentreRef(s.centreId(), s.centreCode(), s.centreName()),
                    st.percent(), st.weeksDone(), videosWatched, videosWatched + " of " + videoIds.size(), minutes, last, Labels.ago(last, today), status, label);
            rows.add(new Computed(s, row, st, mine));
        }
        return new Class(o, ws, is, rows, videoIds.size(), openWeeks);
    }

    public ProgressTable table(CurrentUser cu, UUID offeringId, String filter, String q, UUID centreId) {
        Class c = compute(cu, offeringId);
        List<ProgressRow> all = c.rows().stream().map(Computed::row).toList();
        Counts counts = new Counts(all.size(), count(all, "INACTIVE"), count(all, "BEHIND"), count(all, "NOT_STARTED"));
        String f = filter == null || filter.isBlank() ? "ALL" : filter.toUpperCase(Locale.ROOT);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<ProgressRow> shown = all.stream()
                .filter(r -> f.equals("ALL") || r.status().equals(f))
                .filter(r -> centreId == null || centreId.equals(r.centre().id()))
                .filter(r -> needle.isEmpty() || r.fullName().toLowerCase(Locale.ROOT).contains(needle) || r.matricNumber().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        return new ProgressTable(counts, c.videos(), c.openWeeks(), shown);
    }

    static int count(List<ProgressRow> rows, String status) {
        return (int) rows.stream().filter(r -> r.status().equals(status)).count();
    }

    public ProgressDetail detail(CurrentUser cu, UUID offeringId, UUID studentId) {
        Class c = compute(cu, offeringId);
        Computed s = c.rows().stream().filter(r -> r.student().studentId().equals(studentId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "This student is not registered for " + c.offering().getCourseCode() + " this semester."));
        List<WeekDone> weeksDone = new ArrayList<>();
        for (Week w : c.weeks()) {
            if (w.isHidden()) continue;
            ProgressCalc.WeekState st = s.state().weeks().get(w.getId());
            weeksDone.add(new WeekDone(w.getNumber(), w.getTitle(), st == null ? 0 : st.done(), st == null ? 0 : st.total(), st != null && st.complete()));
        }
        Map<UUID, ItemProgress> mine = s.mine().stream().collect(Collectors.toMap(ItemProgress::getItemId, p -> p, (a, b) -> a));
        List<VideoWatch> videos = c.items().stream().filter(i -> i.getKind() == Item.Kind.VIDEO && !i.isHidden() && i.usable()).map(i -> {
            ItemProgress p = mine.get(i.getId());
            return new VideoWatch(i.getId(), i.getTitle(), p == null ? 0 : (int) Math.round(p.getWatchedSeconds() / 60.0), p == null ? 0 : p.getPercentWatched(),
                    p != null && p.isDone());
        }).toList();
        return new ProgressDetail(s.row(), weeksDone, videos);
    }

    public String csv(CurrentUser cu, UUID offeringId) {
        Class c = compute(cu, offeringId);
        StringBuilder sb = new StringBuilder(com.odel.common.files.Csv.line(List.of("student", "matric_number", "centre", "percent", "weeks_done",
                "videos_watched", "minutes_watched", "last_active", "status")));
        for (Computed x : c.rows()) {
            ProgressRow r = x.row();
            sb.append(com.odel.common.files.Csv.line(List.of(r.fullName(), r.matricNumber(), r.centre().name() == null ? "" : r.centre().name(),
                    Integer.toString(r.percent()), Integer.toString(r.weeksDone()), r.videosLabel(), Integer.toString(r.minutesWatched()),
                    r.lastActiveAt() == null ? "" : r.lastActiveAt().atZone(Labels.LAGOS).toLocalDate().toString(), r.statusLabel())));
        }
        return sb.toString();
    }

    /** The students an announcement to "inactive students" goes to (T-05b). */
    public List<CourseStudent> inactive(Offering o) {
        return compute(o, Optional.empty()).rows().stream().filter(r -> r.row().status().equals("INACTIVE")).map(Computed::student).toList();
    }
}
