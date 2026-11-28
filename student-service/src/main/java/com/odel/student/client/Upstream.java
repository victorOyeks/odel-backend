package com.odel.student.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.ApiException;

import java.util.HashMap;
import java.util.Map;

/** InternalClient hands a 4xx from another service back with its raw body; this turns it into that service's own error. */
final class Upstream {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Upstream() {}

    static ApiException relay(ApiException e) {
        if (!"upstream".equals(e.code())) return e;
        try {
            JsonNode b = JSON.readTree(e.getMessage());
            Map<String, Object> extra = new HashMap<>();
            b.fields().forEachRemaining(f -> {
                if (!f.getKey().equals("error") && !f.getKey().equals("message") && !f.getKey().equals("fields")) extra.put(f.getKey(), JSON.convertValue(f.getValue(), Object.class));
            });
            Map<String, String> fields = new HashMap<>();
            b.path("fields").fields().forEachRemaining(f -> fields.put(f.getKey(), f.getValue().asText()));
            return new ApiException(e.status(), b.path("error").asText("upstream"), b.path("message").asText("Something went wrong. Try again."),
                    fields.isEmpty() ? null : fields, extra.isEmpty() ? null : extra);
        } catch (Exception ignored) {
            return e;
        }
    }
}
