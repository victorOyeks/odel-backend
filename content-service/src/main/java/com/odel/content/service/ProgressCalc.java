package com.odel.content.service;

import com.odel.content.dto.ContentDtos.Progress;
import com.odel.content.entity.Item;
import com.odel.content.entity.ItemProgress;
import com.odel.content.entity.Week;

import java.util.*;

/**
 * One student's progress through an offering. Item done: video 90 % watched, a file or link opened, a page marked done. Week done:
 * every ready, visible item of the week done (a week with nothing ready is not done). Course %: weeks done of the visible weeks.
 */
public final class ProgressCalc {
    private ProgressCalc() {}

    public record WeekState(int done, int total) {
        public boolean complete() { return total > 0 && done == total; }
    }

    public record State(Map<UUID, ItemProgress> byItem, Map<UUID, WeekState> weeks, int weeksDone, int weeksTotal, int itemsDone, int itemsTotal,
                        int percent) {
        public Progress progress() {
            return new Progress(percent, weeksDone, weeksTotal, itemsDone, itemsTotal, weeksDone + " of " + weeksTotal + " weeks done · " + percent + "%");
        }

        public boolean done(UUID itemId) {
            ItemProgress p = byItem.get(itemId);
            return p != null && p.isDone();
        }
    }

    /** Items that count: visible, ready for students, in a visible week. */
    public static boolean counts(Item i, Map<UUID, Week> weeks) {
        Week w = weeks.get(i.getWeekId());
        return w != null && !w.isHidden() && !i.isHidden() && i.usable() && i.getReplacesItemId() == null;
    }

    public static State compute(List<Week> weeks, List<Item> items, Collection<ItemProgress> progress) {
        Map<UUID, Week> byId = new HashMap<>();
        for (Week w : weeks) byId.put(w.getId(), w);
        Map<UUID, ItemProgress> byItem = new HashMap<>();
        for (ItemProgress p : progress) byItem.put(p.getItemId(), p);
        Map<UUID, int[]> counts = new HashMap<>();
        int itemsDone = 0, itemsTotal = 0;
        for (Item i : items) {
            if (!counts(i, byId)) continue;
            int[] c = counts.computeIfAbsent(i.getWeekId(), k -> new int[2]);
            c[1]++;
            itemsTotal++;
            ItemProgress p = byItem.get(i.getId());
            if (p != null && p.isDone()) {
                c[0]++;
                itemsDone++;
            }
        }
        Map<UUID, WeekState> states = new LinkedHashMap<>();
        int weeksDone = 0, weeksTotal = 0;
        for (Week w : weeks) {
            if (w.isHidden()) continue;
            weeksTotal++;
            int[] c = counts.getOrDefault(w.getId(), new int[2]);
            WeekState s = new WeekState(c[0], c[1]);
            states.put(w.getId(), s);
            if (s.complete()) weeksDone++;
        }
        int percent = weeksTotal == 0 ? 0 : (int) Math.round(weeksDone * 100.0 / weeksTotal);
        return new State(byItem, states, weeksDone, weeksTotal, itemsDone, itemsTotal, percent);
    }
}
