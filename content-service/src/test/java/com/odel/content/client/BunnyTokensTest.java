package com.odel.content.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class BunnyTokensTest {
    static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void uploadSignatureIsSha256OfLibraryKeyExpiryAndVideo() throws Exception {
        assertThat(BunnyTokens.uploadSignature("123", "key", 1738900000L, "guid"))
                .isEqualTo(sha256Hex("123key1738900000guid"));
    }

    @Test
    void embedTokenIsSha256OfKeyVideoAndExpiry() throws Exception {
        assertThat(BunnyTokens.embedToken("tk", "guid", 1700000000L)).isEqualTo(sha256Hex("tkguid1700000000"));
    }

    @Test
    void signedUrlPutsTheDirectoryTokenInThePath() {
        String url = BunnyTokens.signedUrl("vz-abc.b-cdn.net", "hs256", "tk", "guid", "playlist.m3u8", 1700000000L);
        assertThat(url).startsWith("https://vz-abc.b-cdn.net/bcdn_token=HS256-")
                .contains("&expires=1700000000&token_path=%2Fguid%2F/guid/playlist.m3u8");
        // the same directory token signs every file of the video (HLS segments inherit it)
        String other = BunnyTokens.signedUrl("vz-abc.b-cdn.net", "hs256", "tk", "guid", "360p/video.m3u8", 1700000000L);
        assertThat(other.substring(0, other.indexOf("/guid/360p"))).isEqualTo(url.substring(0, url.indexOf("/guid/playlist")));
        String legacy = BunnyTokens.cdnToken("sha256", "tk", "/guid/", 1700000000L);
        assertThat(legacy).doesNotStartWith("HS256-").doesNotContain("+", "/", "=");
    }
}
