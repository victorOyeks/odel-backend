package com.odel.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Service-to-service calls (/internal/**) must carry the shared X-Internal-Key; the gateway never routes them. */
public class InternalKeyFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Internal-Key";
    private final byte[] key;

    public InternalKeyFilter(String key) {
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/internal/")) {
            String sent = request.getHeader(HEADER);
            if (key.length == 0 || sent == null || !MessageDigest.isEqual(key, sent.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Internal calls only.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
