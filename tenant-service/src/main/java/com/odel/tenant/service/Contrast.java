package com.odel.tenant.service;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

/** WCAG 2 contrast ratio. University buttons put white text on the primary colour, so it needs 4.5 : 1. */
public final class Contrast {
    public static final double MIN = 4.5;

    private Contrast() {}

    public static boolean isHex(String c) {
        return c != null && c.matches("#[0-9A-Fa-f]{6}");
    }

    public static double ratio(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 255) + 0.7152 * channel((rgb >> 8) & 255) + 0.0722 * channel(rgb & 255);
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    public static String format(double r) {
        return String.format("%.1f : 1", Math.floor(r * 10) / 10);
    }
}
