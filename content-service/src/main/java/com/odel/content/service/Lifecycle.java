package com.odel.content.service;

import com.odel.common.audit.AuditService;
import com.odel.common.tenancy.TenantContext;
import com.odel.content.client.PeerClient;
import com.odel.content.dto.PeerDtos.Message;
import com.odel.content.dto.PeerDtos.StaffView;
import com.odel.content.entity.Item;
import com.odel.content.entity.Offering;
import com.odel.content.repository.ItemRepository;
import com.odel.content.repository.OfferingRepository;
import com.odel.content.repository.WeekRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What happens when an upload is finished (ready, converted, kept or failed): a replacement takes its item's place, the old video is
 * released, and the uploader is told about videos.
 */
@Service
public class Lifecycle {
    private final ItemRepository items;
    private final OfferingRepository offerings;
    private final WeekRepository weeks;
    private final PeerClient peers;
    private final JdbcTemplate jdbc;
    private final com.odel.content.client.VideoGateway videos;
    private final AuditService audit;
    private final Clock clock;

    public Lifecycle(ItemRepository items, OfferingRepository offerings, WeekRepository weeks, PeerClient peers, JdbcTemplate jdbc,
                     com.odel.content.client.VideoGateway videos, AuditService audit, Clock clock) {
        this.items = items;
        this.offerings = offerings;
        this.weeks = weeks;
        this.peers = peers;
        this.jdbc = jdbc;
        this.videos = videos;
        this.audit = audit;
        this.clock = clock;
    }

    /** A finished upload. A replacement that is usable takes its item's place (same id, position and progress); returns the item students see. */
    public Item finished(Item target) {
        if (target.getReplacesItemId() == null || !target.usable()) return target;
        Item original = items.findInTenant(target.getReplacesItemId()).orElse(null);
        if (original == null) return target;
        String oldVideo = original.getVideoId();
        original.setKind(target.getKind());
        ItemCopier.copyContent(target, original);
        original.setUpdatedAt(clock.instant());
        items.delete(target);
        items.flush();
        if (oldVideo != null && !oldVideo.equals(original.getVideoId())) {
            Integer n = jdbc.queryForObject("select count(*) from items where video_id = ?", Integer.class, oldVideo);
            if (n != null && n == 0) videos.delete(oldVideo);
        }
        Offering o = offerings.findInTenant(original.getOfferingId()).orElse(null);
        audit.record(new AuditService.Entry(TenantContext.require(), AuditService.Actor.SYSTEM, "CONTENT_ITEM_REPLACED", "CONTENT", original.getId(),
                (o == null ? "" : o.getCourseCode() + ": ") + "\"" + original.getTitle() + "\" now uses the new file", null, null));
        return original;
    }

    /** Bell (and email for a failure) to the person who uploaded a video. */
    public void tellUploader(Item i, String template) {
        if (i.getCreatedBy() == null) return;
        Offering o = offerings.findInTenant(i.getOfferingId()).orElse(null);
        if (o == null) return;
        String email = null;
        try {
            StaffView s = peers.user(i.getCreatedBy());
            email = s == null ? null : s.email();
        } catch (RuntimeException ignored) {
            // the bell item still goes
        }
        int week = weeks.findInTenant(i.getWeekId()).map(w -> w.getNumber()).orElse(0);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", i.getTitle());
        vars.put("courseCode", o.getCourseCode());
        vars.put("weekNumber", week);
        vars.put("reason", i.getFailureMessage() == null ? "" : i.getFailureMessage());
        String link = "/staff/courses/" + o.getId() + "/content?week=" + i.getWeekId();
        vars.put("contentUrl", peers.portal() + link);
        peers.send(new Message(i.getCreatedBy(), email, template, vars, link, peers.look()));
    }
}
