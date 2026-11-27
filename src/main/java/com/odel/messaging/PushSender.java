package com.odel.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.odel.common.OdelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Firebase Cloud Messaging (HTTP v1) for browser push. Without a project id and service account file
 * it only logs, so development and tests need no Firebase project.
 */
@Component
public class PushSender {
    private static final Logger log = LoggerFactory.getLogger(PushSender.class);
    private final OdelProperties.Push cfg;
    private final ObjectMapper json;
    private final RestClient http = RestClient.create();
    private volatile String accessToken;
    private volatile Instant accessTokenExpires = Instant.EPOCH;

    public enum Result { SENT, TOKEN_GONE, LOGGED }

    public PushSender(OdelProperties props, ObjectMapper json) {
        this.cfg = props.push();
        this.json = json;
    }

    public boolean configured() {
        return cfg.fcmProjectId() != null && !cfg.fcmProjectId().isBlank()
                && cfg.fcmServiceAccountFile() != null && !cfg.fcmServiceAccountFile().isBlank();
    }

    public Result send(String token, String title, String body, String link) throws Exception {
        if (!configured()) {
            log.info("Push (not configured, logged only): {} | {}", title, body);
            return Result.LOGGED;
        }
        Map<String, Object> webpush = link == null || link.isBlank() ? Map.of() : Map.of("fcm_options", Map.of("link", link));
        Map<String, Object> message = Map.of("message", Map.of("token", token,
                "notification", Map.of("title", title, "body", body), "webpush", webpush));
        try {
            http.post().uri("https://fcm.googleapis.com/v1/projects/" + cfg.fcmProjectId() + "/messages:send")
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON).body(message).retrieve().toBodilessEntity();
            return Result.SENT;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404 || e.getResponseBodyAsString().contains("UNREGISTERED")) return Result.TOKEN_GONE;
            throw e;
        }
    }

    private synchronized String token() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpires.minusSeconds(60))) return accessToken;
        JsonNode sa = json.readTree(Files.readString(Path.of(cfg.fcmServiceAccountFile())));
        String pem = sa.get("private_key").asText().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(sa.get("client_email").asText()).audience("https://oauth2.googleapis.com/token")
                .claim("scope", "https://www.googleapis.com/auth/firebase.messaging")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build());
        jwt.sign(new RSASSASigner(key));
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", jwt.serialize());
        JsonNode res = http.post().uri("https://oauth2.googleapis.com/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class);
        accessToken = res.get("access_token").asText();
        accessTokenExpires = now.plusSeconds(res.get("expires_in").asLong(3600));
        return accessToken;
    }
}
