package com.odel.common.files;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature V4 query-string presigning for S3-compatible storage (Cloudflare R2, MinIO).
 * Only the host header is signed, so the browser can send its own Content-Type.
 */
public final class SigV4Presigner {
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final String accessKey;
    private final String secretKey;
    private final String region;

    public SigV4Presigner(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    /**
     * @param scheme "https" or "http"
     * @param host   host as sent in the Host header (with port when it is not the default)
     * @param path   absolute path, already in raw form ("/bucket/logos/a b.svg"); each segment is encoded here
     */
    public String presign(String method, String scheme, String host, String path, Duration expires, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String scope = date + "/" + region + "/s3/aws4_request";

        Map<String, String> query = new TreeMap<>();
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", accessKey + "/" + scope);
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", Long.toString(expires.toSeconds()));
        query.put("X-Amz-SignedHeaders", "host");

        String canonicalQuery = canonicalQuery(query);
        String canonicalPath = encodePath(path);
        String canonicalRequest = method + "\n" + canonicalPath + "\n" + canonicalQuery + "\n"
                + "host:" + host + "\n\n" + "host\n" + "UNSIGNED-PAYLOAD";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));

        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = hex(hmac(key, stringToSign));

        return scheme + "://" + host + canonicalPath + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    private static String canonicalQuery(Map<String, String> sorted) {
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(encode(k)).append('=').append(encode(v));
        });
        return sb.toString();
    }

    static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!sb.isEmpty() || path.startsWith("/")) sb.append('/');
            sb.append(encode(segment));
        }
        String out = sb.toString();
        return out.startsWith("//") ? out.substring(1) : out;
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
