package com.odel.common.security;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.OdelProperties;
import com.odel.common.Tokens;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side sessions in Redis. Key = SHA-256 of the cookie token, so a Redis dump cannot be replayed.
 * The key's TTL is the idle timeout and is renewed on use; sessions also end after the absolute timeout.
 */
@Component
public class SessionStore {
    private static final String KEY = "odel:sess:";
    private static final String USER_INDEX = "odel:user-sess:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final OdelProperties.Session cfg;
    private final Clock clock;

    public SessionStore(StringRedisTemplate redis, ObjectMapper json, OdelProperties props, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.cfg = props.session();
        this.clock = clock;
    }

    public record Found(String key, SessionData data) {}

    /** Returns the cookie token. */
    public String create(SessionData data) {
        String token = Tokens.random(32);
        data.createdAt = clock.instant();
        String key = Tokens.sha256(token);
        write(key, data);
        redis.opsForSet().add(USER_INDEX + data.userId, key);
        redis.expire(USER_INDEX + data.userId, cfg.absoluteTimeout());
        return token;
    }

    public Optional<Found> find(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String key = Tokens.sha256(token);
        String raw = redis.opsForValue().get(KEY + key);
        if (raw == null) return Optional.empty();
        try {
            SessionData data = json.readValue(raw, SessionData.class);
            if (data.createdAt != null && data.createdAt.plus(cfg.absoluteTimeout()).isBefore(clock.instant())) {
                delete(key, data.userId);
                return Optional.empty();
            }
            return Optional.of(new Found(key, data));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public void save(String key, SessionData data) {
        write(key, data);
    }

    public void touch(String key, SessionData data) {
        redis.expire(KEY + key, ttl(data));
    }

    public Duration ttl(SessionData data) {
        return data.stage == SessionData.Stage.SIGNED_IN || data.stage == SessionData.Stage.CHOOSE_ROLE
                ? cfg.idleTimeout() : cfg.signInTimeout();
    }

    public Long remainingSeconds(String key) {
        return redis.getExpire(KEY + key);
    }

    public void delete(String key, UUID userId) {
        redis.delete(KEY + key);
        if (userId != null) redis.opsForSet().remove(USER_INDEX + userId, key);
    }

    /** Signs a person out of every browser (suspension, role removal, "sign out other devices"). */
    public void deleteAllForUser(UUID userId) {
        Set<String> keys = redis.opsForSet().members(USER_INDEX + userId);
        if (keys != null) keys.forEach(k -> redis.delete(KEY + k));
        redis.delete(USER_INDEX + userId);
    }

    /** Every live session of one person (L-14j "Signed in on"); stale index entries are dropped on the way. */
    public java.util.List<Found> list(UUID userId) {
        Set<String> keys = redis.opsForSet().members(USER_INDEX + userId);
        java.util.List<Found> out = new java.util.ArrayList<>();
        if (keys == null) return out;
        for (String k : keys) {
            String raw = redis.opsForValue().get(KEY + k);
            if (raw == null) {
                redis.opsForSet().remove(USER_INDEX + userId, k);
                continue;
            }
            try {
                SessionData data = json.readValue(raw, SessionData.class);
                if (data.createdAt != null && data.createdAt.plus(cfg.absoluteTimeout()).isBefore(clock.instant())) {
                    delete(k, userId);
                    continue;
                }
                out.add(new Found(k, data));
            } catch (JsonProcessingException e) {
                delete(k, userId);
            }
        }
        return out;
    }

    /** "Sign out others": every session of the person but the one given. Returns how many ended. */
    public int deleteAllForUserExcept(UUID userId, String keepKey) {
        int n = 0;
        for (Found f : list(userId)) {
            if (f.key().equals(keepKey)) continue;
            delete(f.key(), userId);
            n++;
        }
        return n;
    }

    private void write(String key, SessionData data) {
        try {
            redis.opsForValue().set(KEY + key, json.writeValueAsString(data), ttl(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
