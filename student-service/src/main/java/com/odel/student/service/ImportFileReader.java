package com.odel.student.service;

import com.odel.common.ApiException;
import com.odel.common.files.LocalStorageService;
import com.odel.common.files.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Reads an uploaded portal file back from storage (local disk in dev, R2 by a signed link in production). */
@Component
public class ImportFileReader {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private final StorageService storage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ImportFileReader(StorageService storage) {
        this.storage = storage;
    }

    public List<CsvParser.Record> read(String key) {
        byte[] bytes = bytes(key);
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not_csv", "This looks like an Excel file. Save it as CSV (File → Save as → CSV UTF-8) and upload it again.");
        }
        try {
            return CsvParser.parse(new java.io.StringReader(decode(bytes)));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not_csv", "We could not read this file as CSV. Export it again from the portal.");
        }
    }

    /** UTF-8, or Windows-1252 for an older Excel export that is not valid UTF-8. */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    private byte[] bytes(String key) {
        try {
            if (storage instanceof LocalStorageService local) {
                Path p = local.path(key);
                if (!Files.exists(p)) throw missing();
                if (Files.size(p) > MAX_BYTES) throw tooBig();
                return Files.readAllBytes(p);
            }
            String url = storage.presignGet(key, Duration.ofMinutes(5));
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() == 404 || res.statusCode() == 403) throw missing();
            if (res.statusCode() != 200) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", "We could not read the file just now. Try again in a minute.");
            try (InputStream in = res.body()) {
                byte[] b = in.readNBytes((int) MAX_BYTES + 1);
                if (b.length > MAX_BYTES) throw tooBig();
                return b;
            }
        } catch (IOException e) {
            throw missing();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw missing();
        }
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.BAD_REQUEST, "file_missing", "We could not find the uploaded file. Upload it again.");
    }

    private static ApiException tooBig() {
        return new ApiException(HttpStatus.BAD_REQUEST, "file_too_large", "The file is over 10 MB. Split it into smaller files.");
    }

    static InputStream stream(byte[] b) {
        return new ByteArrayInputStream(b);
    }
}
