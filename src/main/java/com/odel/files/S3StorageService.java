package com.odel.files;

import com.odel.common.OdelProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;

/** Cloudflare R2 (or any S3-compatible store), with SigV4 presigned links. */
@Service
@ConditionalOnProperty(name = "odel.storage.mode", havingValue = "s3", matchIfMissing = true)
public class S3StorageService implements StorageService {
    private final OdelProperties.Storage cfg;
    private final SigV4Presigner signer;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;

    public S3StorageService(OdelProperties props, Clock clock) {
        this.cfg = props.storage();
        this.clock = clock;
        this.signer = new SigV4Presigner(cfg.accessKey(), cfg.secretKey(), cfg.region());
        this.endpoint = URI.create(cfg.endpoint());
    }

    @Override
    public String presignPut(String key, Duration ttl) {
        return presign("PUT", key, ttl);
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        return presign("GET", key, ttl);
    }

    @Override
    public OptionalLong size(String key) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(presign("HEAD", key, Duration.ofMinutes(1))))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() != 200) return OptionalLong.empty();
            return res.headers().firstValueAsLong("Content-Length");
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    private String presign(String method, String key, Duration ttl) {
        String host = endpoint.getHost() + (endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "");
        String basePath = endpoint.getPath() == null ? "" : endpoint.getPath().replaceAll("/$", "");
        return signer.presign(method, endpoint.getScheme(), host, basePath + "/" + cfg.bucket() + "/" + key, ttl, clock.instant());
    }
}
