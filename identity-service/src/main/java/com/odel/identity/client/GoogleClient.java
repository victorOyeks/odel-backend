package com.odel.identity.client;

import com.odel.identity.dto.GoogleIdentity;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.OdelProperties;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/** Google OpenID Connect: authorization URL, code exchange and id_token verification. */
@Component
public class GoogleClient {
    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String JWKS = "https://www.googleapis.com/oauth2/v3/certs";

    private final OdelProperties.Google cfg;
    private final RestClient http = RestClient.create();
    private volatile NimbusJwtDecoder decoder;

    public GoogleClient(OdelProperties props) {
        this.cfg = props.google();
    }

    public String authorizationUrl(String state, String nonce, String hostedDomain) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(AUTH)
                .queryParam("client_id", cfg.clientId())
                .queryParam("redirect_uri", cfg.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("prompt", "select_account");
        if (hostedDomain != null && !hostedDomain.isBlank()) b.queryParam("hd", hostedDomain);
        return b.encode().build().toUriString();
    }

    @SuppressWarnings("unchecked")
    public GoogleIdentity exchange(String code, String expectedNonce) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("code", code);
        form.add("client_id", cfg.clientId());
        form.add("client_secret", cfg.clientSecret());
        form.add("redirect_uri", cfg.redirectUri());
        form.add("grant_type", "authorization_code");
        Map<String, Object> res = http.post().uri(TOKEN).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        String idToken = res == null ? null : (String) res.get("id_token");
        if (idToken == null) throw new IllegalStateException("Google returned no id_token");
        Jwt jwt = decoder().decode(idToken);
        if (!expectedNonce.equals(jwt.getClaimAsString("nonce"))) throw new IllegalStateException("Nonce mismatch");
        Boolean verified = jwt.getClaim("email_verified");
        return new GoogleIdentity(jwt.getClaimAsString("email"), Boolean.TRUE.equals(verified),
                jwt.getClaimAsString("hd"), jwt.getClaimAsString("name"));
    }

    private NimbusJwtDecoder decoder() {
        if (decoder == null) {
            NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(JWKS).build();
            d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(),
                    jwt -> {
                        String iss = jwt.getClaimAsString("iss");
                        return "https://accounts.google.com".equals(iss) || "accounts.google.com".equals(iss)
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_issuer"));
                    },
                    jwt -> jwt.getAudience().contains(cfg.clientId())
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience"))));
            decoder = d;
        }
        return decoder;
    }
}
