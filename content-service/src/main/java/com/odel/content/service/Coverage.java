package com.odel.content.service;

/**
 * Which parts of a video a student has watched, in 10-second stretches ("0110…"), and the rules built on it: a beat counts at
 * most 60 seconds, seeking counts nothing, done at 90 %.
 */
public final class Coverage {
    public static final int BUCKET = 10;
    public static final int MAX_BEAT = 60;
    public static final int DONE_PERCENT = 90;

    private Coverage() {}

    public static int buckets(int durationSeconds) {
        return Math.max(1, (durationSeconds + BUCKET - 1) / BUCKET);
    }

    /** The stretches watched after playing from..to (seconds), clamped to the video and to one beat. */
    public static String mark(String current, int durationSeconds, int from, int to) {
        int n = buckets(durationSeconds);
        StringBuilder sb = new StringBuilder(current == null ? "" : current);
        if (sb.length() > n) sb.setLength(n);
        while (sb.length() < n) sb.append('0');
        int a = Math.max(0, Math.min(from, durationSeconds)), b = Math.max(0, Math.min(to, durationSeconds));
        if (b <= a) return sb.toString();
        if (b - a > MAX_BEAT) b = a + MAX_BEAT;
        for (int i = a / BUCKET; i < n && i * BUCKET < b; i++) {
            // a stretch counts once playing covered at least half of it (or reached the end of the video)
            int start = i * BUCKET, end = Math.min(durationSeconds, start + BUCKET);
            int covered = Math.min(b, end) - Math.max(a, start);
            if (covered * 2 >= (end - start) || (b >= durationSeconds && end == durationSeconds && covered > 0)) sb.setCharAt(i, '1');
        }
        return sb.toString();
    }

    public static int percent(String buckets) {
        if (buckets == null || buckets.isEmpty()) return 0;
        long ones = buckets.chars().filter(c -> c == '1').count();
        return (int) Math.min(100, Math.round(ones * 100.0 / buckets.length()));
    }

    /** Watch time added by one beat: the media seconds played, at most one beat's worth. */
    public static int watched(int from, int to) {
        return Math.max(0, Math.min(MAX_BEAT, to - from));
    }

    public static boolean done(String buckets) {
        return percent(buckets) >= DONE_PERCENT;
    }
}
