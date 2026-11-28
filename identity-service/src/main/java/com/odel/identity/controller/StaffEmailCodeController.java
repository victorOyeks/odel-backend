package com.odel.identity.controller;

import com.odel.identity.dto.StaffCodeDtos.*;
import com.odel.identity.service.StaffEmailCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

/**
 * Staff sign-in by a code emailed to the work address (universities whose staff sign-in is EMAIL_CODE). No session needed;
 * a right code opens the half-way session and the authenticator two-step follows as after Google.
 */
@RestController
@RequestMapping("/api/v1/auth/email-code")
public class StaffEmailCodeController {
    private final StaffEmailCodeService codes;

    public StaffEmailCodeController(StaffEmailCodeService codes) {
        this.codes = codes;
    }

    @PostMapping("/start")
    public CodeSent start(@RequestBody StartRequest body) {
        return codes.start(body.email(), body.returnTo());
    }

    @PostMapping("/resend")
    public CodeSent resend(@RequestBody FlowRequest body) {
        return codes.resend(body.flowId());
    }

    @PostMapping("/verify")
    public Next verify(@RequestBody VerifyRequest body, HttpServletRequest request, HttpServletResponse response) {
        return new Next(codes.verify(body.flowId(), body.code(), request, response));
    }
}
