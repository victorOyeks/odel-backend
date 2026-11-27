package com.odel.identity;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.identity.SignInService;
import com.odel.identity.Totp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Development only (odel.google.dev-stub=true, never in production): stands in for Google's account
 * picker so sign-in can be tested without a Google OAuth client, and reads authenticator codes.
 */
@RestController
@ConditionalOnProperty(name = "odel.google.dev-stub", havingValue = "true")
public class DevController {
    private final SignInService signIn;

    public DevController(SignInService signIn) {
        this.signIn = signIn;
    }

    @GetMapping(value = "/api/v1/auth/dev/google", produces = MediaType.TEXT_HTML_VALUE)
    public String picker(@RequestParam String state) {
        String s = HtmlUtils.htmlEscape(state);
        return """
                <!doctype html><meta name=viewport content="width=device-width"><title>Dev Google sign-in</title>
                <body style="font-family:sans-serif;max-width:420px;margin:48px auto;padding:0 16px">
                <h2>Dev Google sign-in</h2><p>Stands in for Google in development. Type the Google account email.</p>
                <form method=post action="/api/v1/auth/dev/google"><input type=hidden name=state value="%s">
                <input name=email type=email required autofocus style="width:100%%;padding:10px;font-size:16px" placeholder="ngozi.adebayo@wesleyuni.edu.ng">
                <p><button style="padding:10px 16px;font-size:16px">Continue</button></p></form>
                <p style="color:#666">Seeded: chuka.obi@odel.dev (console)</p>""".formatted(s);
    }

    @PostMapping(value = "/api/v1/auth/dev/google", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> pick(@RequestParam String state, @RequestParam String email) {
        String target = signIn.devCallback(state, email);
        if (target == null) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @GetMapping("/api/v1/dev/totp")
    public Map<String, String> totp(@RequestParam String secret) {
        return Map.of("code", Totp.code(secret, Totp.step(Instant.now())));
    }
}
