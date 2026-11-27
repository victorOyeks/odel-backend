package com.odel.common.files;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;

import com.odel.common.OdelProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.OptionalLong;

/**
 * Development stand-in for R2 (odel.storage.mode=local): files on local disk, served by DevStorageController
 * through the same-origin /api proxy, with the same expiring, signed links the browser gets from R2.
 */
@Service
@ConditionalOnProperty(name = "odel.storage.mode", havingValue = "local")
public class LocalStorageService implements StorageService {
    private final Path root;
    private final byte[] key;
    private final Clock clock;

    public LocalStorageService(@Value("${odel.storage.local-dir:.local-storage}") String dir, OdelProperties props, Clock clock) {
        this.root = Path.of(dir).toAbsolutePath();
        this.key = props.mfa().encryptionKey().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public String presignPut(String key, Duration ttl) { return url("PUT", key, ttl); }

    @Override
    public String presignGet(String key, Duration ttl) { return url("GET", key, ttl); }

    @Override
    public OptionalLong size(String key) {
        try {
            Path p = path(key);
            return Files.exists(p) ? OptionalLong.of(Files.size(p)) : OptionalLong.empty();
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    public Path path(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Bad key");
        return p;
    }

    public boolean valid(String method, String key, long expires, String sig) {
        return expires >= clock.instant().getEpochSecond() && java.security.MessageDigest.isEqual(
                sign(method, key, expires).getBytes(StandardCharsets.UTF_8), sig == null ? new byte[0] : sig.getBytes(StandardCharsets.UTF_8));
    }

    private String url(String method, String k, Duration ttl) {
        long exp = clock.instant().plus(ttl).getEpochSecond();
        return "/api/v1/dev/storage/" + k + "?expires=" + exp + "&sig=" + sign(method, k, exp);
    }

    private String sign(String method, String k, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((method + "\n" + k + "\n" + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
