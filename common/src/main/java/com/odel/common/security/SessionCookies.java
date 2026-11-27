package com.odel.common.security;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.OdelProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SessionCookies {
    public static final String SESSION = "ODEL_SESSION";
    public static final String TRUST = "ODEL_TRUST";

    private final OdelProperties.Session cfg;

    public SessionCookies(OdelProperties props) {
        this.cfg = props.session();
    }

    public static String read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    public void setSession(HttpServletResponse response, String token) {
        add(response, SESSION, token, null, "/");
    }

    public void clearSession(HttpServletResponse response) {
        add(response, SESSION, "", Duration.ZERO, "/");
    }

    public void setTrust(HttpServletResponse response, String token) {
        add(response, TRUST, token, Duration.ofDays(cfg.trustDeviceDays()), "/api/v1/auth");
    }

    private void add(HttpServletResponse response, String name, String value, Duration maxAge, String path) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .httpOnly(true).secure(cfg.secureCookies()).sameSite("Lax").path(path);
        if (maxAge != null) b.maxAge(maxAge);
        response.addHeader(HttpHeaders.SET_COOKIE, b.build().toString());
    }
}
