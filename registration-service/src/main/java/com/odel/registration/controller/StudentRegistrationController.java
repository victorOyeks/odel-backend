package com.odel.registration.controller;

import com.odel.common.security.CurrentUserArg;
import com.odel.registration.dto.RegistrationDtos.*;
import com.odel.registration.dto.RequestDtos.*;
import com.odel.registration.service.RegistrationService;
import com.odel.registration.service.RequestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** The student's registration (R-01 … R-09e). Active role STUDENT only. */
@RestController
@RequestMapping("/api/v1/registration/me")
public class StudentRegistrationController {
    private final RegistrationService registration;
    private final RequestService requests;

    public StudentRegistrationController(RegistrationService registration, RequestService requests) {
        this.registration = registration;
        this.requests = requests;
    }

    @GetMapping
    public Overview overview(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId) {
        return registration.overview(cu.user(), semesterId);
    }

    @GetMapping("/courses")
    public CourseChoice courses(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId) {
        return registration.courseChoice(cu.user(), semesterId);
    }

    @PostMapping("/check")
    public CheckResult check(CurrentUserArg.Holder cu, @RequestBody CheckRequest body) {
        return registration.check(cu.user(), body);
    }

    @PostMapping("/submit")
    public ResponseEntity<RegistrationView> submit(CurrentUserArg.Holder cu, @RequestBody SubmitRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registration.submit(cu.user(), body));
    }

    @GetMapping("/registrations")
    public Registrations registrations(CurrentUserArg.Holder cu) {
        return registration.mine(cu.user());
    }

    @GetMapping("/registrations/{id}")
    public RegistrationView registration(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return registration.mine(cu.user(), id);
    }

    @GetMapping("/registrations/{id}/slip.pdf")
    public ResponseEntity<byte[]> slip(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return Pdf.of(registration.mySlip(cu.user(), id));
    }

    @PostMapping("/uploads")
    public UploadLink upload(CurrentUserArg.Holder cu, @RequestBody UploadRequest body) {
        return requests.uploadLink(cu.user(), body);
    }

    @PostMapping("/late-requests")
    public ResponseEntity<RequestView> late(CurrentUserArg.Holder cu, @RequestBody LateRequestIn body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.late(cu.user(), body));
    }

    @PostMapping("/change-requests")
    public ResponseEntity<RequestView> change(CurrentUserArg.Holder cu, @RequestBody ChangeRequestIn body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.change(cu.user(), body));
    }

    @GetMapping("/requests")
    public Requests myRequests(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId) {
        return requests.mine(cu.user(), semesterId);
    }

    @GetMapping("/requests/{id}")
    public RequestView myRequest(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return requests.one(cu.user(), id);
    }

    @GetMapping("/requests/{id}/attachment")
    public Link attachment(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return requests.myAttachment(cu.user(), id);
    }

    @PostMapping("/requests/{id}/cancel")
    public RequestView cancel(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return requests.cancel(cu.user(), id);
    }
}
