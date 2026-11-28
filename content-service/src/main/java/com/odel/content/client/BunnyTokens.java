package com.odel.content.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/** Bunny Stream's signatures: the TUS upload signature, the embed player token and the CDN directory token. */
public final class BunnyTokens {
    private BunnyTokens() {}

    /** TUS upload: hex sha256(libraryId + apiKey + expires + videoId). */
    public static String uploadSignature(String libraryId, String apiKey, long expires, String videoId) {
        return HexFormat.of().formatHex(sha256(libraryId + apiKey + expires + videoId));
    }

    /** Embed view token: hex sha256(tokenKey + videoId + expires). */
    public static String embedToken(String tokenKey, String videoId, long expires) {
        return HexFormat.of().formatHex(sha256(tokenKey + videoId + expires));
    }

    /**
     * CDN advanced token for a whole directory (token_path), so HLS segments are allowed too.
     * hs256: "HS256-" + base64url(HMAC-SHA256(key, path + expires + "token_path=" + path)); sha256: base64url(SHA256(key + path + expires + "token_path=" + path)).
     */
    public static String cdnToken(String format, String tokenKey, String tokenPath, long expires) {
        String data = tokenPath + expires + "token_path=" + tokenPath;
        if ("sha256".equalsIgnoreCase(format)) return b64url(sha256(tokenKey + data));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "HS256-" + b64url(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** https://<cdn>/bcdn_token=<t>&expires=<e>&token_path=%2F<id>%2F/<id>/<file>: the token sits in the path, so relative segment URLs keep it. */
    public static String signedUrl(String cdnHost, String format, String tokenKey, String videoId, String file, long expires) {
        String dir = "/" + videoId + "/";
        String token = cdnToken(format, tokenKey, dir, expires);
        return "https://" + cdnHost + "/bcdn_token=" + token + "&expires=" + expires + "&token_path="
                + URLEncoder.encode(dir, StandardCharsets.UTF_8) + dir + file;
    }

    static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
