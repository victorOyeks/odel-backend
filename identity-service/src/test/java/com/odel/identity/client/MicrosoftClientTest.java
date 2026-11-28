package com.odel.identity.client;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.odel.identity.dto.SignInIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Microsoft ID tokens: signature, audience, expiry, the per-organisation issuer, the nonce and which claim gives the email. */
class MicrosoftClientTest {
    static final String CLIENT = "11111111-2222-3333-4444-555555555555";
    static final String TID = "72f988bf-86f1-41af-91ab-2d7cd011db47";
    static KeyPair keys, otherKeys;
    static NimbusJwtDecoder decoder;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        otherKeys = g.generateKeyPair();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(MicrosoftClient.validator(CLIENT));
    }

    static String token(KeyPair signer, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder()
                .issuer("https://login.microsoftonline.com/" + TID + "/v2.0")
                .audience(CLIENT)
                .subject("abc")
                .issueTime(Date.from(now.minusSeconds(10)))
                .notBeforeTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("tid", TID)
                .claim("nonce", "n-1")
                .claim("preferred_username", "Ngozi.Adebayo@WesleyUni.edu.ng")
                .claim("name", "Ngozi Adebayo");
        change.accept(c);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), c.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) signer.getPrivate()));
        return jwt.serialize();
    }

    static Jwt decode(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        return decoder.decode(token(keys, change));
    }

    interface Decode { Jwt run() throws Exception; }

    /** The validation errors (code and description) a token fails with. */
    static java.util.List<String> errors(Decode d) {
        try {
            d.run();
            return java.util.List.of();
        } catch (JwtValidationException e) {
            return e.getErrors().stream().flatMap(x -> java.util.stream.Stream.of(x.getErrorCode(), String.valueOf(x.getDescription()))).toList();
        } catch (org.springframework.security.oauth2.jwt.BadJwtException e) {
            return java.util.List.of("bad_jwt", e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aGoodTokenGivesTheWorkEmail() throws Exception {
        SignInIdentity id = MicrosoftClient.identity(decode(c -> {}), "n-1");
        assertThat(id.email()).isEqualTo("ngozi.adebayo@wesleyuni.edu.ng");
        assertThat(id.emailVerified()).isTrue();
        assertThat(id.emailDomain()).isEqualTo("wesleyuni.edu.ng");
    }

    @Test
    void theIssuerMustBeExactlyTheTokensOwnOrganisation() {
        // another organisation's id in the issuer
        assertThat(errors(() -> decode(c -> c.issuer("https://login.microsoftonline.com/00000000-0000-0000-0000-000000000001/v2.0")))).contains("invalid_issuer");
        // starts like Microsoft's issuer but is not it
        assertThat(errors(() -> decode(c -> c.issuer("https://login.microsoftonline.com/" + TID + "/v2.0.evil.example")))).contains("invalid_issuer");
        assertThat(errors(() -> decode(c -> c.issuer("https://login.microsoftonline.com.evil.example/" + TID + "/v2.0")))).contains("invalid_issuer");
        // the v1 issuer is not accepted from the v2 endpoint
        assertThat(errors(() -> decode(c -> c.issuer("https://sts.windows.net/" + TID + "/")))).contains("invalid_issuer");
        // the {tenantid} template must never be accepted literally
        assertThat(errors(() -> decode(c -> c.issuer("https://login.microsoftonline.com/{tenantid}/v2.0").claim("tid", "{tenantid}")))).isNotEmpty();
        assertThat(errors(() -> decode(c -> c.claim("tid", null)))).contains("missing_tenant");
    }

    @Test
    void personalMicrosoftAccountsAreRefused() {
        String consumers = MicrosoftClient.CONSUMERS_TENANT;
        assertThat(errors(() -> decode(c -> c.issuer("https://login.microsoftonline.com/" + consumers + "/v2.0").claim("tid", consumers)))).contains("personal_account");
    }

    @Test
    void audienceExpiryAndSignatureAreChecked() {
        assertThat(errors(() -> decode(c -> c.audience("someone-elses-app")))).contains("invalid_audience");
        assertThat(errors(() -> decode(c -> c.issueTime(Date.from(Instant.now().minusSeconds(4000))).notBeforeTime(Date.from(Instant.now().minusSeconds(4000)))
                .expirationTime(Date.from(Instant.now().minusSeconds(600)))))).anyMatch(e -> e.contains("expired"));
        assertThatThrownBy(() -> decoder.decode(token(otherKeys, c -> {}))).hasMessageContaining("signature");
    }

    @Test
    void theNonceMustMatchThisSignIn() throws Exception {
        Jwt jwt = decode(c -> {});
        assertThatThrownBy(() -> MicrosoftClient.identity(jwt, "n-2")).hasMessageContaining("Nonce");
        assertThatThrownBy(() -> MicrosoftClient.identity(jwt, null)).hasMessageContaining("Nonce");
    }

    @Test
    void theEmailClaimIsTrustedOnlyWhenMicrosoftVerifiedItsDomain() throws Exception {
        // no upn / preferred_username: an unverified email claim (any admin can type one) gives no identity
        Jwt unverified = decode(c -> c.claim("preferred_username", null).claim("email", "ngozi.adebayo@wesleyuni.edu.ng"));
        assertThat(MicrosoftClient.identity(unverified, "n-1").emailVerified()).isFalse();
        assertThat(MicrosoftClient.identity(unverified, "n-1").email()).isNull();
        Jwt verified = decode(c -> c.claim("preferred_username", null).claim("email", "ngozi.adebayo@wesleyuni.edu.ng").claim("xms_edov", true));
        assertThat(MicrosoftClient.identity(verified, "n-1").email()).isEqualTo("ngozi.adebayo@wesleyuni.edu.ng");
        // upn first; a guest's #EXT# name is not an address
        Jwt upn = decode(c -> c.claim("upn", "n.adebayo@wesleyuni.edu.ng").claim("preferred_username", "other@wesleyuni.edu.ng"));
        assertThat(MicrosoftClient.identity(upn, "n-1").email()).isEqualTo("n.adebayo@wesleyuni.edu.ng");
        Jwt guest = decode(c -> c.claim("upn", "ngozi_gmail.com#EXT#@contoso.onmicrosoft.com").claim("preferred_username", null));
        assertThat(MicrosoftClient.identity(guest, "n-1").emailVerified()).isFalse();
    }
}
