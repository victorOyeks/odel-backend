package com.odel.identity.controller;

import com.odel.common.tenancy.TenantContext;
import com.odel.identity.dto.SessionView;
import com.odel.identity.dto.TenantInfo;
import com.odel.identity.dto.StudentAuthDtos.*;
import com.odel.identity.service.PasswordRules;
import com.odel.identity.service.StudentAuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.odel.identity.service.StudentCodeStore.Purpose;

/** Students get in: activation (M-01 … M-05), sign-in (M-08), forgot password (M-09 … M-11). No session needed. */
@RestController
@RequestMapping("/api/v1/auth/student")
public class StudentAuthController {
    private final StudentAuthService auth;
    private final TenantInfo.Client tenants;

    public StudentAuthController(StudentAuthService auth, TenantInfo.Client tenants) {
        this.auth = auth;
        this.tenants = tenants;
    }

    @GetMapping("/password-rules")
    public Map<String, Object> passwordRules() {
        return Map.of("minLength", PasswordRules.MIN_LENGTH, "hint", PasswordRules.HINT, "rules", PasswordRules.RULES);
    }

    @GetMapping("/privacy-notice")
    public Map<String, Object> privacyNotice() {
        var id = TenantContext.current();
        String name = TenantContext.NONE.equals(id) ? "The university" : tenants.get(id).name();
        return Map.of("version", StudentAuthService.PRIVACY_VERSION, "title", "How we use your information",
                "intro", name + " protects your data under the Nigeria Data Protection Act 2023.",
                "points", List.of("Your records are used to run your programme.", "Reminders and codes come by email and in the app.",
                        "Staff see only what they need to support you.", "We never sell your data. Ask for a copy any time."));
    }

    // ---- activation ----

    @PostMapping("/activation/start")
    public Map<String, Object> activationStart(@RequestBody StartRequest body) {
        return view("activationId", auth.start(Purpose.ACTIVATION, body.login(), false));
    }

    @PostMapping("/activation/resend")
    public Map<String, Object> activationResend(@RequestBody FlowRefRequest body) {
        return view("activationId", auth.resend(Purpose.ACTIVATION, body.activationId()));
    }

    @PostMapping("/activation/verify")
    public Map<String, Object> activationVerify(@RequestBody VerifyCodeRequest body) {
        var v = auth.verify(Purpose.ACTIVATION, body.activationId(), body.code());
        return Map.of("activationToken", v.token(), "expiresInSeconds", v.expiresInSeconds(), "student", v.student());
    }

    @PostMapping("/activation/password")
    public ResponseEntity<Void> activationPassword(@RequestBody PasswordRequest body) {
        auth.activationPassword(body.activationToken(), body.password(), body.confirmPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/activation/complete")
    public SessionView activationComplete(@RequestBody CompleteActivationRequest body, HttpServletResponse response) {
        return auth.completeActivation(body.activationToken(), Boolean.TRUE.equals(body.acceptPrivacyNotice()), body.privacyNoticeVersion(),
                Boolean.TRUE.equals(body.keepSignedIn()), response);
    }

    // ---- sign in ----

    @PostMapping("/sign-in")
    public SessionView signIn(@RequestBody SignInRequest body, HttpServletResponse response) {
        return auth.signIn(body.login(), body.password(), Boolean.TRUE.equals(body.keepSignedIn()), response);
    }

    // ---- forgot password ----

    @PostMapping("/password-reset/start")
    public Map<String, Object> resetStart(@RequestBody StartRequest body) {
        return view("resetId", auth.start(Purpose.RESET, body.login(), Boolean.TRUE.equals(body.codeAlreadySent())));
    }

    @PostMapping("/password-reset/resend")
    public Map<String, Object> resetResend(@RequestBody FlowRefRequest body) {
        return view("resetId", auth.resend(Purpose.RESET, body.resetId()));
    }

    @PostMapping("/password-reset/verify")
    public Map<String, Object> resetVerify(@RequestBody VerifyCodeRequest body) {
        var v = auth.verify(Purpose.RESET, body.resetId(), body.code());
        return Map.of("resetToken", v.token(), "expiresInSeconds", v.expiresInSeconds(), "student", v.student());
    }

    @PostMapping("/password-reset/complete")
    public SessionView resetComplete(@RequestBody PasswordRequest body, HttpServletResponse response) {
        return auth.completeReset(body.resetToken(), body.password(), body.confirmPassword(), Boolean.TRUE.equals(body.keepSignedIn()), response);
    }

    private static Map<String, Object> view(String idName, CodeSent s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(idName, s.flowId());
        m.put("maskedEmail", s.maskedEmail());
        m.put("codeSent", s.codeSent());
        m.put("expiresInSeconds", s.expiresInSeconds());
        m.put("resendInSeconds", s.resendInSeconds());
        m.put("sendsLeftThisHour", s.sendsLeftThisHour());
        m.put("student", Map.of("firstName", s.student().firstName()));
        m.put("centre", s.centre());
        return m;
    }
}
