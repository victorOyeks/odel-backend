package com.odel.identity;

import com.odel.tenancy.ResolvedHost;
import com.odel.tenancy.TenantFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final SignInService signIn;
    private final MfaService mfa;

    public AuthController(SignInService signIn, MfaService mfa) {
        this.signIn = signIn;
        this.mfa = mfa;
    }

    @GetMapping("/google/start")
    public ResponseEntity<Void> start(@RequestParam(required = false) String returnTo, HttpServletRequest request) {
        ResolvedHost host = TenantFilter.resolved(request);
        if (!host.known()) return ResponseEntity.notFound().build();
        return redirect(signIn.start(host, returnTo));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                                           @RequestParam(required = false) String error) {
        String target = state == null ? null : signIn.callback(state, code, error);
        if (target == null) return expiredPage();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @GetMapping("/handoff")
    public ResponseEntity<Void> handoff(@RequestParam String code, HttpServletRequest request, HttpServletResponse response) {
        return redirect(signIn.handoff(code, TenantFilter.resolved(request), request, response));
    }

    public record CodeRequest(String code, Boolean trustDevice) {
        boolean trust() { return Boolean.TRUE.equals(trustDevice); }
    }

    @PostMapping("/mfa/setup")
    public MfaService.Setup setup(CurrentUserArg.Holder cu) {
        return mfa.setup(cu.user());
    }

    @PostMapping("/mfa/setup/confirm")
    public MfaService.Confirmed confirm(CurrentUserArg.Holder cu, @RequestBody CodeRequest body, HttpServletResponse response) {
        return mfa.confirm(cu.user(), body.code(), response);
    }

    @PostMapping("/mfa/verify")
    public SessionView verify(CurrentUserArg.Holder cu, @RequestBody CodeRequest body, HttpServletResponse response) {
        return mfa.verify(cu.user(), body.code(), body.trust(), response).session();
    }

    @PostMapping("/mfa/backup")
    public BackupVerified backup(CurrentUserArg.Holder cu, @RequestBody CodeRequest body, HttpServletResponse response) {
        MfaService.Verified v = mfa.backup(cu.user(), body.code(), body.trust(), response);
        return new BackupVerified(v.session(), v.backupCodesLeft());
    }

    public record BackupVerified(SessionView session, Integer backupCodesLeft) {}

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    static ResponseEntity<String> expiredPage() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_HTML)
                .body("<!doctype html><meta name=viewport content='width=device-width'><title>Sign-in expired</title>"
                        + "<p style='font-family:sans-serif;padding:24px'>This sign-in link has expired. Go back to your portal and choose "
                        + "<b>Continue with university Google account</b> again.</p>");
    }
}
