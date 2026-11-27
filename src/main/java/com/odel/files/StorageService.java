package com.odel.files;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * Documents live in Cloudflare R2. The API never streams files: it hands the browser short-lived
 * signed links after checking permission. Videos go to Bunny Stream, not here.
 */
public interface StorageService {
    String presignPut(String key, Duration ttl);

    String presignGet(String key, Duration ttl);

    /** Size of a stored object, or empty if it is not there (used to confirm a browser upload). */
    OptionalLong size(String key);
}
