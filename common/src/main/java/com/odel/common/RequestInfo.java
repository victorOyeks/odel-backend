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
        return r == null ? null : clientIp(r.getHeader("X-Real-IP"), r.getHeader("X-Forwarded-For"), r.getRemoteAddr());
    }

    /**
     * The browser's address: nginx's X-Real-IP (it sets it from the connection, so a client cannot fake it), else the first
     * X-Forwarded-For entry, else the peer. Behind nginx → web → gateway the peer is always 127.0.0.1.
     */
    public static String clientIp(String realIp, String forwardedFor, String remoteAddr) {
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        if (forwardedFor != null && !forwardedFor.isBlank()) return forwardedFor.split(",")[0].trim();
        return remoteAddr;
    }

    public static String userAgent() {
        HttpServletRequest r = current();
        if (r == null) return null;
        String ua = r.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 500));
    }
}
