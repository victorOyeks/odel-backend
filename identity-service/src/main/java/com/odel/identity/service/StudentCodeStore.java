package com.odel.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.Tokens;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Redis state for students' emailed codes: the live code (hashed) per purpose, when codes were sent (the hourly limit),
 * the flow id the screen holds between M-02 and M-03, and the token that carries a verified student to the password step.
 */
@Component
public class StudentCodeStore {
    private static final String CODE = "odel:stu:code:";
    private static final String SENDS = "odel:stu:sends:";
    private static final String FLOW = "odel:stu:flow:";
    private static final String FLOW_FOR = "odel:stu:flow-for:";
    private static final String TOKEN = "odel:stu:token:";
    public static final Duration FLOW_TTL = Duration.ofMinutes(30);

    public enum Purpose { ACTIVATION, RESET }

    public record Flow(UUID tenantId, UUID userId, Purpose purpose) {}

    public record Verified(UUID tenantId, UUID userId, Purpose purpose, String passwordHash) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public StudentCodeStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public static String hash(UUID userId, String code) {
        return Tokens.sha256(userId + ":" + (code == null ? "" : code.replaceAll("\\s", "")));
    }

    // ---- codes ----

    public Optional<CodePolicy.Stored> code(Purpose p, UUID userId) {
        return read(CODE + p + ":" + userId, CodePolicy.Stored.class);
    }

    public void putCode(Purpose p, UUID userId, CodePolicy.Stored stored) {
        Duration ttl = Duration.between(Instant.now(), stored.expiresAt()).plusMinutes(1);
        write(CODE + p + ":" + userId, stored, ttl.isNegative() ? Duration.ofMinutes(1) : ttl);
    }

    public void deleteCode(Purpose p, UUID userId) {
        redis.delete(CODE + p + ":" + userId);
    }

    public List<Instant> sends(UUID userId, Instant now) {
        String key = SENDS + userId;
        redis.opsForZSet().removeRangeByScore(key, 0, now.minus(CodePolicy.WINDOW).toEpochMilli());
        Set<String> all = redis.opsForZSet().range(key, 0, -1);
        return all == null ? List.of() : all.stream().map(s -> Instant.ofEpochMilli(Long.parseLong(s.split(":")[0]))).toList();
    }

    public void recordSend(UUID userId, Instant now) {
        String key = SENDS + userId;
        redis.opsForZSet().add(key, now.toEpochMilli() + ":" + Tokens.random(4), now.toEpochMilli());
        redis.expire(key, CodePolicy.WINDOW.plusMinutes(5));
    }

    // ---- flows (activationId / resetId) ----

    public String flowFor(Purpose p, UUID tenantId, UUID userId) {
        String existing = redis.opsForValue().get(FLOW_FOR + p + ":" + userId);
        if (existing != null && redis.hasKey(FLOW + existing)) {
            redis.expire(FLOW + existing, FLOW_TTL);
            return existing;
        }
        String id = Tokens.random(24);
        write(FLOW + id, new Flow(tenantId, userId, p), FLOW_TTL);
        redis.opsForValue().set(FLOW_FOR + p + ":" + userId, id, FLOW_TTL);
        return id;
    }

    public Optional<Flow> flow(String id) {
        return id == null || id.isBlank() ? Optional.empty() : read(FLOW + id, Flow.class);
    }

    public void endFlow(Purpose p, UUID userId) {
        String id = redis.opsForValue().getAndDelete(FLOW_FOR + p + ":" + userId);
        if (id != null) redis.delete(FLOW + id);
    }

    // ---- verified tokens ----

    public String newToken(Verified v) {
        String token = Tokens.random(32);
        write(TOKEN + Tokens.sha256(token), v, FLOW_TTL);
        return token;
    }

    public Optional<Verified> token(String token) {
        return token == null || token.isBlank() ? Optional.empty() : read(TOKEN + Tokens.sha256(token), Verified.class);
    }

    public void saveToken(String token, Verified v) {
        write(TOKEN + Tokens.sha256(token), v, FLOW_TTL);
    }

    public void deleteToken(String token) {
        redis.delete(TOKEN + Tokens.sha256(token));
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(json.readValue(raw, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
