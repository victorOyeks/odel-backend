package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.VideoGateway;
import com.odel.content.dto.ContentDtos.*;
import com.odel.content.entity.Item;
import com.odel.content.entity.Offering;
import com.odel.content.entity.Week;
import com.odel.content.repository.ItemRepository;
import com.odel.content.repository.ProgressRepository;
import com.odel.content.repository.WeekRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

/** The week editor (T-03 …): weeks, links and pages, rename, hide, move, reorder, delete. Uploads are in UploadService. */
@Service
public class BuilderService {
    private final Directory directory;
    private final AccessService access;
    private final WeekRepository weeks;
    private final ItemRepository items;
    private final ProgressRepository progress;
    private final VideoGateway videos;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public BuilderService(Directory directory, AccessService access, WeekRepository weeks, ItemRepository items, ProgressRepository progress,
                          VideoGateway videos, JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.directory = directory;
        this.access = access;
        this.weeks = weeks;
        this.items = items;
        this.progress = progress;
        this.videos = videos;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    void log(CurrentUser cu, String action, UUID id, String summary) {
        audit.record(new AuditService.Entry(TenantContext.require(), access.actor(cu), action, "CONTENT", id, summary, null, null));
    }

    public Week week(UUID id) {
        return weeks.findInTenant(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "We could not find that week. Reload the course."));
    }

    public Item item(UUID id) {
        Item i = items.findInTenant(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "We could not find that item. Reload the week."));
        if (i.getReplacesItemId() != null) throw ApiException.notFound();
        return i;
    }

    // ---- weeks

    @Transactional(readOnly = true)
    public WeekList weeks(CurrentUser cu, UUID offeringId) {
        Offering o = directory.offering(offeringId);
        access.level(cu, o);
        LocalDate today = directory.today();
        List<Item> all = items.forOffering(o.getId());
        return new WeekList(weeks.forOffering(o.getId()).stream().map(w -> {
            List<Item> in = all.stream().filter(i -> i.getWeekId().equals(w.getId())).toList();
            int ready = (int) in.stream().filter(Item::usable).count();
            return new WeekRow(w.getId(), w.getNumber(), w.getTitle(), w.getOpensOn(), w.openOn(today), w.isHidden(), in.size(), ready,
                    Views.weekStatus(w, in.size(), today), w.getNote());
        }).toList());
    }

    static Map<String, String> checkWeek(WeekIn in) {
        Map<String, String> f = new LinkedHashMap<>();
        if (in.title() == null || in.title().isBlank()) f.put("title", "Give the week a title, e.g. \"Linked lists\".");
        else if (in.title().trim().length() > 120) f.put("title", "Keep the title to 120 characters.");
        if (in.note() != null && in.note().length() > 1000) f.put("note", "Keep the note to 1,000 characters.");
        return f;
    }

    @Transactional
    public WeekView addWeek(CurrentUser cu, UUID offeringId, WeekIn in) {
        Offering o = directory.offering(offeringId);
        access.edit(cu, o);
        Map<String, String> f = checkWeek(in);
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        Week w = new Week();
        w.setId(UUID.randomUUID());
        w.setOfferingId(o.getId());
        w.setNumber(weeks.lastNumber(o.getId()) + 1);
        w.setTitle(in.title().trim());
        w.setOpensOn(in.opensOn());
        w.setNote(blankToNull(in.note()));
        w.setHidden(Boolean.TRUE.equals(in.hidden()));
        w.setCreatedAt(clock.instant());
        w.setUpdatedAt(clock.instant());
        weeks.save(w);
        log(cu, "CONTENT_WEEK_ADDED", w.getId(), o.getCourseCode() + ": week " + w.getNumber() + " \"" + w.getTitle() + "\" added");
        String msg = "Week " + w.getNumber() + " added" + (w.getOpensOn() == null ? "" : " · opens " + Labels.day(w.getOpensOn()));
        return view(w, true, msg);
    }

    @Transactional(readOnly = true)
    public WeekView getWeek(CurrentUser cu, UUID id) {
        Week w = week(id);
        access.level(cu, directory.offering(w.getOfferingId()));
        return view(w, false, null);
    }

    @Transactional
    public WeekView updateWeek(CurrentUser cu, UUID id, WeekIn in) {
        Week w = week(id);
        Offering o = directory.offering(w.getOfferingId());
        access.edit(cu, o);
        Map<String, String> f = checkWeek(in);
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        LocalDate before = w.getOpensOn();
        w.setTitle(in.title().trim());
        w.setOpensOn(in.opensOn());
        w.setNote(blankToNull(in.note()));
        if (in.hidden() != null) w.setHidden(in.hidden());
        w.setUpdatedAt(clock.instant());
        log(cu, "CONTENT_WEEK_CHANGED", w.getId(), o.getCourseCode() + ": week " + w.getNumber() + " changed"
                + (Objects.equals(before, w.getOpensOn()) ? "" : ", opens " + (w.getOpensOn() == null ? "no date" : Labels.day(w.getOpensOn()))));
        String msg = w.isHidden() ? "Week " + w.getNumber() + " is hidden from students"
                : w.getOpensOn() == null ? "Week " + w.getNumber() + " saved · no open date"
                : "Week " + w.getNumber() + (w.openOn(directory.today()) ? " is open to students" : " now opens " + Labels.day(w.getOpensOn()));
        return view(w, true, msg);
    }

    @Transactional
    public void deleteWeek(CurrentUser cu, UUID id) {
        Week w = week(id);
        Offering o = directory.offering(w.getOfferingId());
        access.edit(cu, o);
        long n = items.countInWeek(w.getId());
        if (n > 0) throw new ApiException(HttpStatus.CONFLICT, "not_empty", "Move or delete this week's " + n + (n == 1 ? " item" : " items") + " first.");
        jdbc.update("delete from items where week_id = ?", w.getId());   // stray replacement uploads (the week is this university's)
        weeks.delete(w);
        log(cu, "CONTENT_WEEK_DELETED", w.getId(), o.getCourseCode() + ": week " + w.getNumber() + " \"" + w.getTitle() + "\" deleted");
    }

    WeekView view(Week w, boolean edit, String message) {
        var now = clock.instant();
        List<StaffItem> list = items.forWeek(w.getId()).stream().map(i -> staffItem(i, null)).toList();
        return new WeekView(w.getId(), w.getOfferingId(), w.getNumber(), w.getTitle(), w.getOpensOn(), w.openOn(directory.today()), w.isHidden(), w.getNote(),
                Views.releaseLabel(w, directory.today()), list, message);
    }

    public StaffItem staffItem(Item i, String message) {
        Item rep = items.replacementsOf(i.getId()).stream().findFirst().orElse(null);
        return Views.staff(i, progress.openedBy(i.getId()), rep, clock.instant(), message);
    }

    // ---- links and pages

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static Map<String, String> checkContent(Item.Kind kind, ItemIn in) {
        Map<String, String> f = new LinkedHashMap<>();
        if (in.title() == null || in.title().isBlank()) f.put("title", "Give it a title students will recognise.");
        else if (in.title().trim().length() > 200) f.put("title", "Keep the title to 200 characters.");
        if (kind == Item.Kind.LINK) {
            String u = in.url() == null ? "" : in.url().trim();
            if (!u.matches("(?i)https?://[^\\s/$.?#][^\\s]*") || u.length() > 2000) f.put("url", "Paste the full web address, starting with https://");
            if (in.note() != null && in.note().length() > 1000) f.put("note", "Keep the note to 1,000 characters.");
        }
        if (kind == Item.Kind.PAGE) {
            if (in.body() == null || in.body().isBlank()) f.put("body", "Write the page first.");
            else if (in.body().length() > 20_000) f.put("body", "Keep a page to 20,000 characters. Split it into two pages or upload a PDF.");
        }
        return f;
    }

    @Transactional
    public StaffItem addItem(CurrentUser cu, UUID weekId, ItemIn in) {
        Week w = week(weekId);
        Offering o = directory.offering(w.getOfferingId());
        access.edit(cu, o);
        Item.Kind kind;
        try {
            kind = Item.Kind.valueOf(in.kind() == null ? "" : in.kind().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            kind = null;
        }
        if (kind != Item.Kind.LINK && kind != Item.Kind.PAGE) {
            throw ApiException.badRequest("bad_kind", "Add a link or a text page here. Upload videos and files with Upload files.");
        }
        Map<String, String> f = checkContent(kind, in);
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        var now = clock.instant();
        Item i = new Item();
        i.setId(UUID.randomUUID());
        i.setOfferingId(o.getId());
        i.setWeekId(w.getId());
        i.setPosition(items.lastPosition(w.getId()) + 1);
        i.setKind(kind);
        i.setTitle(in.title().trim());
        i.setStatus(Item.Status.READY);
        if (kind == Item.Kind.LINK) {
            i.setUrl(in.url().trim());
            i.setNote(blankToNull(in.note()));
            i.setWarnData(in.warnData() == null || in.warnData());
        } else {
            i.setBody(in.body().trim());
        }
        i.setCreatedBy(cu.userId());
        i.setCreatedByName(access.actor(cu).name());
        i.setCreatedAt(now);
        i.setUpdatedAt(now);
        i.setReadyAt(now);
        items.save(i);
        log(cu, "CONTENT_ITEM_ADDED", i.getId(), o.getCourseCode() + " week " + w.getNumber() + ": " + kind.name().toLowerCase(Locale.ROOT) + " \"" + i.getTitle() + "\" added");
        return staffItem(i, (kind == Item.Kind.LINK ? "Link" : "Page") + " added to Week " + w.getNumber());
    }

    @Transactional
    public StaffItem update(CurrentUser cu, UUID id, ItemIn in) {
        Item i = item(id);
        Offering o = directory.offering(i.getOfferingId());
        access.edit(cu, o);
        Map<String, String> f = new LinkedHashMap<>();
        ItemIn merged = new ItemIn(i.getKind().name(), in.title() == null ? i.getTitle() : in.title(), in.url() == null ? i.getUrl() : in.url(),
                in.note() == null ? i.getNote() : in.note(), in.warnData(), in.body() == null ? i.getBody() : in.body());
        f.putAll(checkContent(i.getKind(), merged));
        if (!f.isEmpty()) throw ApiException.fields(HttpStatus.BAD_REQUEST, "invalid", f.values().iterator().next(), f);
        i.setTitle(merged.title().trim());
        if (i.getKind() == Item.Kind.LINK) {
            i.setUrl(merged.url().trim());
            i.setNote(blankToNull(merged.note()));
            if (in.warnData() != null) i.setWarnData(in.warnData());
        }
        if (i.getKind() == Item.Kind.PAGE) i.setBody(merged.body().trim());
        i.setUpdatedAt(clock.instant());
        log(cu, "CONTENT_ITEM_CHANGED", i.getId(), o.getCourseCode() + ": \"" + i.getTitle() + "\" changed");
        return staffItem(i, "Saved");
    }

    @Transactional
    public StaffItem hide(CurrentUser cu, UUID id, boolean hidden) {
        Item i = item(id);
        Offering o = directory.offering(i.getOfferingId());
        access.edit(cu, o);
        i.setHidden(hidden);
        i.setUpdatedAt(clock.instant());
        log(cu, hidden ? "CONTENT_ITEM_HIDDEN" : "CONTENT_ITEM_SHOWN", i.getId(), o.getCourseCode() + ": \"" + i.getTitle() + "\" " + (hidden ? "hidden from" : "shown to") + " students");
        return staffItem(i, hidden ? "Hidden from students" : "Visible to students");
    }

    @Transactional
    public StaffItem move(CurrentUser cu, UUID id, MoveIn in) {
        Item i = item(id);
        Offering o = directory.offering(i.getOfferingId());
        access.edit(cu, o);
        if (in == null || in.weekId() == null) throw ApiException.badRequest("invalid", "Choose the week to move it to.");
        Week to = week(in.weekId());
        if (!to.getOfferingId().equals(o.getId())) throw ApiException.badRequest("other_course", "You can only move items to another week of this course.");
        if (to.getId().equals(i.getWeekId())) return staffItem(i, "Already in Week " + to.getNumber());
        Week from = week(i.getWeekId());
        i.setWeekId(to.getId());
        i.setPosition(items.lastPosition(to.getId()) + 1);
        i.setUpdatedAt(clock.instant());
        for (Item r : items.replacementsOf(i.getId())) r.setWeekId(to.getId());
        items.flush();
        renumber(from.getId());
        log(cu, "CONTENT_ITEM_MOVED", i.getId(), o.getCourseCode() + ": \"" + i.getTitle() + "\" moved from week " + from.getNumber() + " to week " + to.getNumber());
        return staffItem(i, "Moved to Week " + to.getNumber());
    }

    void renumber(UUID weekId) {
        int p = 1;
        for (Item x : items.forWeek(weekId)) x.setPosition(p++);
    }

    @Transactional
    public WeekView reorder(CurrentUser cu, UUID weekId, OrderIn in) {
        Week w = week(weekId);
        Offering o = directory.offering(w.getOfferingId());
        access.edit(cu, o);
        List<Item> list = items.forWeek(w.getId());
        List<UUID> ids = in == null || in.itemIds() == null ? List.of() : in.itemIds();
        Set<UUID> have = new HashSet<>();
        list.forEach(i -> have.add(i.getId()));
        if (ids.size() != list.size() || !have.equals(new HashSet<>(ids))) {
            throw ApiException.badRequest("order_mismatch", "The week changed while you were moving things. Reload the week and try again.");
        }
        Map<UUID, Item> byId = new HashMap<>();
        list.forEach(i -> byId.put(i.getId(), i));
        int p = 1;
        for (UUID x : ids) byId.get(x).setPosition(p++);
        log(cu, "CONTENT_ITEM_MOVED", w.getId(), o.getCourseCode() + ": week " + w.getNumber() + " reordered");
        items.flush();
        return view(w, true, "Order saved");
    }

    @Transactional
    public void delete(CurrentUser cu, UUID id) {
        Item i = item(id);
        Offering o = directory.offering(i.getOfferingId());
        access.edit(cu, o);
        List<Item> gone = new ArrayList<>(items.replacementsOf(i.getId()));
        gone.add(i);
        for (Item x : gone) {
            items.delete(x);
            items.flush();
            releaseVideo(x.getVideoId());
        }
        renumber(i.getWeekId());
        log(cu, "CONTENT_ITEM_DELETED", i.getId(), o.getCourseCode() + ": " + i.getKind().name().toLowerCase(Locale.ROOT) + " \"" + i.getTitle() + "\" deleted");
    }

    /** Deletes a video at the host once no item (here or in a copied course) uses it. Stored files stay (storage has no delete yet). */
    public void releaseVideo(String videoId) {
        if (videoId == null) return;
        Integer n = jdbc.queryForObject("select count(*) from items where video_id = ?", Integer.class, videoId);
        if (n != null && n == 0) videos.delete(videoId);
    }
}
