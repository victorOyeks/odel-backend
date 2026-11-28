package com.odel.payment.controller;

import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.service.PaymentService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** The student's fee and payments (P-01 … P-11). Role STUDENT; every payment is the student's own (404 otherwise). */
@RestController
@RequestMapping("/api/v1/payments")
public class StudentPaymentController {
    private final PaymentService payments;

    public StudentPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    private static CurrentUserArg.Holder student(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.STUDENT);
        return cu;
    }

    @GetMapping("/fees/current")
    public FeeView current(CurrentUserArg.Holder cu) {
        return payments.current(student(cu).user());
    }

    @GetMapping("/fees")
    public Items<FeeView> fees(CurrentUserArg.Holder cu) {
        return payments.all(student(cu).user());
    }

    @PostMapping
    public ResponseEntity<PaymentView> start(CurrentUserArg.Holder cu, @RequestBody StartRequest body) {
        PaymentService.Started s = payments.start(student(cu).user(), body);
        return ResponseEntity.status(s.created() ? HttpStatus.CREATED : HttpStatus.OK).body(s.payment());
    }

    @GetMapping("/mine")
    public Items<PaymentView> mine(CurrentUserArg.Holder cu) {
        return payments.mine(student(cu).user());
    }

    /** The web app's /payments/return page, when Paystack sends the browser back. Shows status only. */
    @GetMapping("/return")
    public PaymentView returned(CurrentUserArg.Holder cu, @RequestParam String reference) {
        return payments.returned(student(cu).user(), reference);
    }

    @GetMapping("/{id}")
    public PaymentView get(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return payments.get(student(cu).user(), id);
    }

    @PostMapping("/{id}/sent")
    public PaymentView sent(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return payments.sent(student(cu).user(), id);
    }

    @PostMapping("/{id}/check")
    public PaymentView check(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return payments.check(student(cu).user(), id);
    }

    @PostMapping("/{id}/cancel")
    public PaymentView cancel(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return payments.cancel(student(cu).user(), id);
    }

    /** The student's own receipt, or any of the university's for the payments roles. */
    @GetMapping("/{id}/receipt.pdf")
    public ResponseEntity<byte[]> receipt(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        boolean staff = cu.user().activeRole() != Role.STUDENT;
        if (staff) Access.require(cu.user(), StaffFeeController.ROLES);
        PaymentService.Pdf pdf = payments.receipt(cu.user(), id, staff);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(pdf.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(pdf.bytes());
    }
}
