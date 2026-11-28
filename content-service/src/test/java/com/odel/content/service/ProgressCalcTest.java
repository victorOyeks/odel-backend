package com.odel.content.service;

import com.odel.content.entity.Item;
import com.odel.content.entity.ItemProgress;
import com.odel.content.entity.Week;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressCalcTest {
    static Week week(int n, boolean hidden) {
        Week w = new Week();
        w.setId(UUID.randomUUID());
        w.setNumber(n);
        w.setHidden(hidden);
        w.setOpensOn(LocalDate.of(2027, 1, 11).plusWeeks(n - 1));
        return w;
    }

    static Item item(Week w, Item.Status s, boolean hidden) {
        Item i = new Item();
        i.setId(UUID.randomUUID());
        i.setWeekId(w.getId());
        i.setKind(Item.Kind.PDF);
        i.setStatus(s);
        i.setHidden(hidden);
        return i;
    }

    static ItemProgress done(Item i) {
        ItemProgress p = new ItemProgress();
        p.setItemId(i.getId());
        p.setDone(true);
        return p;
    }

    @Test
    void weeksDoneOfVisibleWeeks() {
        List<Week> ws = new ArrayList<>();
        for (int n = 1; n <= 13; n++) ws.add(week(n, false));
        ws.add(week(14, true));   // hidden: not counted
        List<Item> is = new ArrayList<>();
        List<ItemProgress> ps = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            Item a = item(ws.get(n), Item.Status.READY, false);
            Item notReady = item(ws.get(n), Item.Status.PROCESSING, false);   // not counted until ready
            Item hidden = item(ws.get(n), Item.Status.READY, true);           // not counted
            is.addAll(List.of(a, notReady, hidden));
            ps.add(done(a));
        }
        Item open = item(ws.get(4), Item.Status.READY, false);
        Item open2 = item(ws.get(4), Item.Status.KEPT_ORIGINAL, false);
        is.addAll(List.of(open, open2));
        ps.add(done(open));
        ProgressCalc.State s = ProgressCalc.compute(ws, is, ps);
        assertThat(s.weeksTotal()).isEqualTo(13);
        assertThat(s.weeksDone()).isEqualTo(4);
        assertThat(s.percent()).isEqualTo(31);
        assertThat(s.progress().label()).isEqualTo("4 of 13 weeks done · 31%");
        assertThat(s.weeks().get(ws.get(4).getId())).isEqualTo(new ProgressCalc.WeekState(1, 2));
        assertThat(s.weeks().get(ws.get(5).getId()).complete()).as("an empty week is not done").isFalse();
        assertThat(s.itemsDone()).isEqualTo(5);
        assertThat(s.itemsTotal()).isEqualTo(6);
    }
}
