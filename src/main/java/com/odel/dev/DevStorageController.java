package com.odel.dev;

import com.odel.files.LocalStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Serves LocalStorageService's signed links in development. */
@RestController
@ConditionalOnProperty(name = "odel.storage.mode", havingValue = "local")
public class DevStorageController {
    private static final String PREFIX = "/api/v1/dev/storage/";
    private final LocalStorageService storage;

    public DevStorageController(LocalStorageService storage) {
        this.storage = storage;
    }

    @PutMapping(PREFIX + "**")
    public ResponseEntity<Void> put(HttpServletRequest request, @RequestParam long expires, @RequestParam String sig) throws IOException {
        String key = key(request);
        if (!storage.valid("PUT", key, expires, sig)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Path p = storage.path(key);
        Files.createDirectories(p.getParent());
        try (var in = request.getInputStream()) {
            Files.copy(in, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping(PREFIX + "**")
    public ResponseEntity<byte[]> get(HttpServletRequest request, @RequestParam long expires, @RequestParam String sig) throws IOException {
        String key = key(request);
        if (!storage.valid("GET", key, expires, sig)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Path p = storage.path(key);
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        MediaType type = key.endsWith(".svg") ? MediaType.valueOf("image/svg+xml")
                : key.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(type)
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(Files.readAllBytes(p));
    }

    private static String key(HttpServletRequest request) {
        return request.getRequestURI().substring(PREFIX.length());
    }
}
