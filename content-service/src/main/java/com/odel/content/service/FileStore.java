package com.odel.content.service;

import com.odel.common.files.LocalStorageService;
import com.odel.common.files.StorageService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Files in storage (R2, or local disk in dev) for the background jobs: fetch an upload to a temporary file, put a converted file back.
 * Browsers never come through here: they get signed links.
 */
@Component
public class FileStore {
    private final StorageService storage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FileStore(StorageService storage) {
        this.storage = storage;
    }

    public static String key(UUID tenantId, UUID offeringId, UUID itemId, String name) {
        return "content/" + tenantId + "/" + offeringId + "/" + itemId + "/" + name;
    }

    public String uploadLink(String key, Duration ttl) {
        return storage.presignPut(key, ttl);
    }

    public String downloadLink(String key, Duration ttl) {
        return storage.presignGet(key, ttl);
    }

    public OptionalLong size(String key) {
        return storage.size(key);
    }

    public void download(String key, Path dest) throws IOException {
        if (storage instanceof LocalStorageService local) {
            Files.copy(local.path(key), dest, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(storage.presignGet(key, Duration.ofMinutes(30)))).timeout(Duration.ofMinutes(10)).GET().build();
            HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
            if (res.statusCode() != 200) throw new IOException("storage answered " + res.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public void put(String key, Path file, String contentType) throws IOException {
        if (storage instanceof LocalStorageService local) {
            Path p = local.path(key);
            Files.createDirectories(p.getParent());
            Files.copy(file, p, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(storage.presignPut(key, Duration.ofMinutes(30))))
                    .header("Content-Type", contentType).timeout(Duration.ofMinutes(10)).PUT(HttpRequest.BodyPublishers.ofFile(file)).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) throw new IOException("storage answered " + res.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
