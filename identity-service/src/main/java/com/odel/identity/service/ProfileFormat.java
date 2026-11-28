package com.odel.identity.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Plain-English bits for the profile notices and the signed-in devices list (L-14j). Pure, unit-tested. */
public final class ProfileFormat {
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private ProfileFormat() {}

    /** "Sat 28 Nov at 2:14 pm" (Lagos time). */
    public static String when(Instant at) {
        var t = at.atZone(LAGOS);
        return DAY.format(t) + " at " + TIME.format(t).toLowerCase(Locale.ROOT);
    }

    /** "Chrome on Windows", "Android phone", "Safari on iPhone"; "A browser" when the agent says nothing useful. */
    public static String device(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "A browser";
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String os = ua.contains("android") ? "Android" : ua.contains("iphone") ? "iPhone" : ua.contains("ipad") ? "iPad"
                : ua.contains("windows") ? "Windows" : ua.contains("mac os") || ua.contains("macintosh") ? "Mac"
                : ua.contains("cros") ? "Chromebook" : ua.contains("linux") ? "Linux" : null;
        String browser = ua.contains("edg/") ? "Edge" : ua.contains("opr/") || ua.contains("opera") ? "Opera"
                : ua.contains("firefox/") ? "Firefox" : ua.contains("samsungbrowser") ? "Samsung Internet"
                : ua.contains("chrome/") || ua.contains("crios/") ? "Chrome" : ua.contains("safari/") ? "Safari" : null;
        if ("Android".equals(os) && (browser == null || ua.contains("; wv)") || ua.contains("okhttp") || ua.contains("dalvik"))) return "Android phone";
        if (os == null && browser == null) return "A browser";
        if (os == null) return browser;
        if (browser == null) return "Android".equals(os) ? "Android phone" : "A browser on " + os;
        return browser + " on " + os;
    }

    /** "102.89.•.•" — enough to tell two places apart, not a full address. */
    public static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        if (ip.contains(".")) {
            String[] p = ip.split("\\.");
            return p.length == 4 ? p[0] + "." + p[1] + ".•.•" : null;
        }
        String[] p = ip.split(":");
        return p.length > 2 ? p[0] + ":" + p[1] + ":•" : null;
    }

    /** "This browser only", "This browser and an Android phone", "This browser and 2 other devices". */
    public static String summary(java.util.List<String> otherDevices) {
        if (otherDevices.isEmpty()) return "This browser only";
        if (otherDevices.size() == 1) {
            String d = otherDevices.get(0);
            if (d.equals("Android phone")) return "This browser and an Android phone";
            if (d.startsWith("A ")) return "This browser and a" + d.substring(1);
            return "This browser and " + d;
        }
        return "This browser and " + otherDevices.size() + " other devices";
    }
}
