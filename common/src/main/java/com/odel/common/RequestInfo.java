package com.odel.common;

import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Client IP and browser for the audit log. */
public final class RequestInfo {
    private RequestInfo() {}

    public static HttpServletRequest current() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }

    public static String ip() {
        HttpServletRequest r = current();
        if (r == null) return null;
        String fwd = r.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return r.getRemoteAddr();
    }

    public static String userAgent() {
        HttpServletRequest r = current();
        if (r == null) return null;
        String ua = r.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 500));
    }
}
