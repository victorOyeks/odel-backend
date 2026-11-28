package com.odel.identity.client;

import com.odel.common.OdelProperties;
import com.odel.identity.dto.SignInIdentity;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Microsoft 365 (Entra ID) OpenID Connect for universities whose staff use Microsoft: authorization URL, code exchange
 * and ID token verification against Microsoft's published keys. One multi-tenant app serves every university
 * (authority .../organizations: work and school accounts only), so the issuer differs per organisation and is checked
 * exactly against the token's own tenant id, never by prefix.
 */
@Component
public class MicrosoftClient {
    static final String LOGIN = "https://login.microsoftonline.com/";
    /** The tenant id Microsoft uses for personal accounts (outlook.com, hotmail.com): never a university's staff. */
    static final String CONSUMERS_TENANT = "9188040d-6c67-4c5b-b112-36a304b66dad";
    private static final Pattern GUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final OdelProperties.Microsoft cfg;
    private final RestClient http = RestClient.create();
    private volatile NimbusJwtDecoder decoder;

    public MicrosoftClient(OdelProperties props) {
        this.cfg = props.microsoft();
    }

    /** True when the platform's Microsoft app is registered (ODEL_MICROSOFT_CLIENT_ID and secret set). */
    public boolean configured() {
        return cfg != null && notBlank(cfg.clientId()) && notBlank(cfg.clientSecret());
    }

    /** https://login.microsoftonline.com/organizations (the configured authority without a trailing /v2.0). */
    String base() {
        String a = cfg == null || !notBlank(cfg.authority()) ? LOGIN + "organizations" : cfg.authority().trim();
        a = a.replaceAll("/+$", "");
        if (a.endsWith("/v2.0")) a = a.substring(0, a.length() - "/v2.0".length());
        return a;
    }

    /** domainHint (the staff email domain) sends the person straight to their organisation's sign-in page. */
    public String authorizationUrl(String state, String nonce, String domainHint) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(base() + "/oauth2/v2.0/authorize")
                .queryParam("client_id", cfg.clientId())
                .queryParam("redirect_uri", cfg.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("response_mode", "query")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("prompt", "select_account");
        if (notBlank(domainHint)) b.queryParam("domain_hint", domainHint);
        return b.encode().build().toUriString();
    }

    @SuppressWarnings("unchecked")
    public SignInIdentity exchange(String code, String expectedNonce) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("code", code);
        form.add("client_id", cfg.clientId());
        form.add("client_secret", cfg.clientSecret());
        form.add("redirect_uri", cfg.redirectUri());
        form.add("grant_type", "authorization_code");
        form.add("scope", "openid email profile");
        Map<String, Object> res = http.post().uri(base() + "/oauth2/v2.0/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        String idToken = res == null ? null : (String) res.get("id_token");
        if (idToken == null) throw new IllegalStateException("Microsoft returned no id_token");
        return identity(decoder().decode(idToken), expectedNonce);
    }

    private NimbusJwtDecoder decoder() {
        if (decoder == null) {
            // Microsoft's keys are the same for every organisation; Nimbus caches them and refetches on an unknown key id.
            NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(base() + "/discovery/v2.0/keys").build();
            d.setJwtValidator(validator(cfg.clientId()));
            decoder = d;
        }
        return decoder;
    }

    // ---- verification rules (static so they are unit-tested with tokens we sign ourselves) ----

    /** Expiry and not-before (with Spring's default clock skew), audience = our app, and the per-organisation issuer. */
    public static OAuth2TokenValidator<Jwt> validator(String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                MicrosoftClient::checkIssuer,
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience")));
    }

    /**
     * The issuer must be exactly https://login.microsoftonline.com/{tid}/v2.0 for the token's own tid (a GUID), and not the
     * personal-accounts tenant. A prefix check would let https://login.microsoftonline.com/x/v2.0.evil through.
     */
    static OAuth2TokenValidatorResult checkIssuer(Jwt jwt) {
        String tid = jwt.getClaimAsString("tid");
        String iss = jwt.getClaimAsString("iss");
        if (tid == null || !GUID.matcher(tid).matches()) return fail("missing_tenant");
        if (CONSUMERS_TENANT.equals(tid)) return fail("personal_account");
        if (!(LOGIN + tid + "/v2.0").equals(iss)) return fail("invalid_issuer");
        return OAuth2TokenValidatorResult.success();
    }

    /**
     * The work email: upn or preferred_username (their domain must be one the organisation has proved it owns in Entra,
     * so another organisation cannot claim wesleyuni.edu.ng addresses), or the email claim when Microsoft marks its domain
     * as verified (xms_edov). An unverified email claim alone is not trusted: an organisation's admin can type any
     * address there.
     */
    public static SignInIdentity identity(Jwt jwt, String expectedNonce) {
        if (expectedNonce == null || !expectedNonce.equals(jwt.getClaimAsString("nonce"))) throw new IllegalStateException("Nonce mismatch");
        String email = null;
        for (String claim : List.of("upn", "preferred_username")) {
            String v = jwt.getClaimAsString(claim);
            if (looksLikeEmail(v)) { email = v; break; }
        }
        Object edov = jwt.getClaims().get("xms_edov");
        boolean emailDomainVerified = Boolean.TRUE.equals(edov) || "true".equalsIgnoreCase(String.valueOf(edov)) || "1".equals(String.valueOf(edov));
        if (email == null && emailDomainVerified && looksLikeEmail(jwt.getClaimAsString("email"))) email = jwt.getClaimAsString("email");
        return new SignInIdentity(email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT), email != null, jwt.getClaimAsString("name"));
    }

    private static boolean looksLikeEmail(String v) {
        return v != null && v.matches("^[^@\\s#]+@[^@\\s]+\\.[^@\\s]+$");
    }

    private static OAuth2TokenValidatorResult fail(String code) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
