package com.odel.common.files;

import com.odel.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Reads back a file the browser uploaded straight to storage (a CSV to import). Local disk in development,
 * a short-lived signed GET on R2 otherwise. Files over the limit are refused before they are read.
 */
@Component
public class StoredFiles {
    private final StorageService storage;
    private final RestClient http = RestClient.create();

    public StoredFiles(StorageService storage) {
        this.storage = storage;
    }

    public byte[] read(String key, long maxBytes) {
        var size = storage.size(key);
        if (size.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "upload_missing", "We could not find the uploaded file. Upload it again.");
        }
        if (size.getAsLong() > maxBytes) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_too_large", "The file is too large. Split it into smaller files.");
        }
        try {
            if (storage instanceof LocalStorageService local) return Files.readAllBytes(local.path(key));
            byte[] body = http.get().uri(java.net.URI.create(storage.presignGet(key, Duration.ofMinutes(5)))).retrieve().body(byte[].class);
            return body == null ? new byte[0] : body;
        } catch (IOException | RuntimeException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable", "We could not read the uploaded file. Try again in a minute.");
        }
    }
}
