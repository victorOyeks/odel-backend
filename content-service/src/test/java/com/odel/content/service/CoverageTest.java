package com.odel.content.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageTest {
    @Test
    void playingMarksTheStretchesWatched() {
        String b = Coverage.mark(null, 100, 0, 30);
        assertThat(b).isEqualTo("1110000000");
        assertThat(Coverage.percent(b)).isEqualTo(30);
    }

    @Test
    void aBeatCountsAtMostSixtySecondsAndSeekingCountsNothing() {
        String b = Coverage.mark(null, 600, 0, 600);   // one beat claiming the whole video
        assertThat(Coverage.percent(b)).isEqualTo(10);
        assertThat(Coverage.watched(0, 600)).isEqualTo(60);
        assertThat(Coverage.mark(b, 600, 300, 300)).isEqualTo(b);   // a jump: from == to
        assertThat(Coverage.watched(300, 290)).isZero();
    }

    @Test
    void doneAtNinetyPercentNotBefore() {
        String b = null;
        for (int t = 0; t < 1080; t += 30) b = Coverage.mark(b, 1084, t, t + 30);   // 18 min of an 18:04 video
        assertThat(Coverage.percent(b)).isBetween(90, 100);
        assertThat(Coverage.done(b)).isTrue();
        String part = null;
        for (int t = 0; t < 900; t += 30) part = Coverage.mark(part, 1084, t, t + 30);
        assertThat(Coverage.done(part)).isFalse();
    }

    @Test
    void theShortLastStretchCountsWhenPlayedToTheEnd() {
        String b = Coverage.mark(null, 25, 0, 20);
        assertThat(b).isEqualTo("110");
        assertThat(Coverage.mark(b, 25, 20, 25)).isEqualTo("111");
    }

    @Test
    void outOfRangeIsClamped() {
        assertThat(Coverage.mark(null, 30, -10, 14)).isEqualTo("100");   // 10–14 is under half of the second stretch
        assertThat(Coverage.mark("1", 30, 25, 99)).isEqualTo("101");
    }
}
