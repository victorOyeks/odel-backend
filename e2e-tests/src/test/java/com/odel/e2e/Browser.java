package com.odel.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A pretend browser on one portal host (e.g. wesley.localhost:3000). Talks to the API the way the
 * Next.js proxy does: X-Odel-Host header, cookies, CSRF header, redirects followed by hand.
 */
public class Browser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String gateway;
    public String host;
    public final Map<String, String> cookies = new LinkedHashMap<>();

    /** gateway e.g. http://localhost:9300; host is the portal the browser pretends to be on. */
    public Browser(String gateway, String host) {
        this.gateway = gateway;
        this.host = host;
    }

    public record Res(int status, String body, String location) {
        public JsonNode json() {
            try { return JSON.readTree(body); } catch (Exception e) { throw new IllegalStateException(body, e); }
        }
    }

    public Res get(String path) { return send("GET", path, null, null); }

    public Res post(String path, Object body) { return send("POST", path, body, "application/json"); }

    public Res delete(String path, Object body) { return send("DELETE", path, body, "application/json"); }

    public Res form(String path, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return send("POST", path, sb.toString(), "application/x-www-form-urlencoded");
    }

    public Res send(String method, String path, Object body, String contentType) {
        try {
            if (!"GET".equals(method) && !cookies.containsKey("XSRF-TOKEN")) get("/api/v1/public/context");
            String payload = body == null ? null : body instanceof String s ? s : JSON.writeValueAsString(body);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(gateway + path))
                    .header("X-Odel-Host", host)
                    .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
            if (contentType != null && payload != null) b.header("Content-Type", contentType);
            if (!cookies.isEmpty()) {
                StringBuilder c = new StringBuilder();
                cookies.forEach((k, v) -> c.append(c.isEmpty() ? "" : "; ").append(k).append('=').append(v));
                b.header("Cookie", c.toString());
            }
            if (cookies.containsKey("XSRF-TOKEN")) b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (String sc : r.headers().allValues("set-cookie")) {
                String pair = sc.split(";", 2)[0];
                String name = pair.substring(0, pair.indexOf('='));
                String value = pair.substring(pair.indexOf('=') + 1);
                if (value.isEmpty() || sc.toLowerCase().contains("max-age=0")) cookies.remove(name); else cookies.put(name, value);
            }
            return new Res(r.statusCode(), r.body(), r.headers().firstValue("location").orElse(null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A POST with the cookies but no CSRF header, to prove it is refused. */
    public int sendRaw(String method, String path) {
        try {
            StringBuilder c = new StringBuilder();
            cookies.forEach((k, v) -> c.append(c.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            HttpRequest r = HttpRequest.newBuilder(URI.create(gateway + path)).header("X-Odel-Host", host)
                    .header("Cookie", c.toString()).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString("{}")).build();
            return http.send(r, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Follows an absolute redirect to a portal host ("http://wesley.localhost:3000/api/..."), switching host. */
    public Res follow(String location) {
        if (location.startsWith("http")) {
            URI u = URI.create(location);
            host = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            return get(u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()));
        }
        return get(location);
    }

    /** Google sign-in through the dev stub. Returns the final redirect (to /auth/continue or the error page). */
    public String googleSignIn(String email) {
        return providerSignIn("google", email);
    }

    /** Microsoft sign-in through the dev stand-in (same as Google's). */
    public String microsoftSignIn(String email) {
        return providerSignIn("microsoft", email);
    }

    private String providerSignIn(String provider, String email) {
        Res start = get("/api/v1/auth/" + provider + "/start?returnTo=/staff");
        if (!start.location().contains("state=")) return start.location(); // refused before leaving the portal (wrong_method)
        String state = start.location().substring(start.location().indexOf("state=") + 6);
        Res picked = form("/api/v1/auth/dev/" + provider, Map.of("state", state, "email", email));
        if (!picked.location().contains("/api/v1/auth/handoff")) return picked.location();
        return follow(picked.location()).location();
    }
}
