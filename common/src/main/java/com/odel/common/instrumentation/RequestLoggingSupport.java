package com.odel.common.instrumentation;

import com.odel.common.security.CurrentUser;
import com.odel.common.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

public final class RequestLoggingSupport {

    private RequestLoggingSupport() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real : request.getRemoteAddr();
    }

    /** The signed-in user's id and active role, or "anonymous": what the request log shows (no names in the log). */
    public static String resolveUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof CurrentUser cu) {
            return cu.userId() + (cu.activeRole() == null ? "" : " " + cu.activeRole().name());
        }
        return "anonymous";
    }

    /** The university the request was for (its id), or "-". */
    public static String resolveTenant() {
        var t = TenantContext.current();
        return TenantContext.NONE.equals(t) ? "-" : t.toString();
    }
}
