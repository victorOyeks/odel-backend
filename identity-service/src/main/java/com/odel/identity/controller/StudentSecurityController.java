package com.odel.identity.controller;

import com.odel.common.security.CurrentUserArg;
import com.odel.identity.dto.StudentSecurityDtos.*;
import com.odel.identity.service.StudentAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The student's own account from My profile (B3, L-14d … L-14j). Students only, except the signed-in devices list which works for
 * anyone signed in. The record itself (phone, address, photo, corrections) is student-service's /student-profile.
 */
@RestController
@RequestMapping("/api/v1/me")
public class StudentSecurityController {
    private final StudentAccountService accounts;

    public StudentSecurityController(StudentAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/security")
    public Security security(CurrentUserArg.Holder cu) {
        return accounts.security(cu.user());
    }

    @PostMapping("/email-change")
    public EmailCodeSent emailChange(CurrentUserArg.Holder cu, @RequestBody EmailChangeRequest body) {
        return accounts.startEmailChange(cu.user(), body.newEmail(), body.password());
    }

    @PostMapping("/email-change/resend")
    public EmailCodeSent emailChangeResend(CurrentUserArg.Holder cu, @RequestBody EmailChangeRef body) {
        return accounts.resendEmailChange(cu.user(), body.changeId());
    }

    @PostMapping("/email-change/verify")
    public EmailChanged emailChangeVerify(CurrentUserArg.Holder cu, @RequestBody EmailChangeVerify body) {
        return accounts.verifyEmailChange(cu.user(), body.changeId(), body.code());
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(CurrentUserArg.Holder cu, @RequestBody PasswordChange body) {
        accounts.changePassword(cu.user(), body.currentPassword(), body.newPassword(), body.confirmPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/recovery-codes")
    public RecoveryCodes recoveryCodes(CurrentUserArg.Holder cu) {
        return accounts.recoveryCodes(cu.user());
    }

    @PostMapping("/recovery-codes")
    public RecoveryCodes makeRecoveryCodes(CurrentUserArg.Holder cu, @RequestBody PasswordOnly body) {
        return accounts.makeRecoveryCodes(cu.user(), body.password());
    }

    @GetMapping("/sessions")
    public Sessions sessions(CurrentUserArg.Holder cu) {
        return accounts.sessions(cu.user());
    }

    @PostMapping("/sessions/sign-out-others")
    public SignedOut signOutOthers(CurrentUserArg.Holder cu) {
        return new SignedOut(accounts.signOutOthers(cu.user()));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> signOutOne(CurrentUserArg.Holder cu, @PathVariable String id) {
        accounts.signOutOne(cu.user(), id);
        return ResponseEntity.noContent().build();
    }
}
