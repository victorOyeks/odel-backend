package com.odel.student.controller;

import com.odel.common.PageResponse;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.student.dto.WelcomeDtos.*;
import com.odel.student.service.WelcomeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** W-20 Send welcome messages, W-20d Edit the welcome email, W-20c the delivery report. */
@RestController
@RequestMapping("/api/v1/students/welcome")
public class WelcomeController {
    private final WelcomeService welcome;

    public WelcomeController(WelcomeService welcome) {
        this.welcome = welcome;
    }

    @GetMapping
    public Overview overview(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return welcome.overview();
    }

    @PutMapping("/template")
    public Overview save(CurrentUserArg.Holder cu, @RequestBody TemplateRequest body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return welcome.saveTemplate(cu.user(), body);
    }

    @DeleteMapping("/template")
    public Overview reset(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return welcome.resetTemplate(cu.user());
    }

    @PostMapping("/test")
    public TestResult test(CurrentUserArg.Holder cu, @RequestBody(required = false) TestRequest body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return welcome.test(cu.user(), body);
    }

    @PostMapping("/sends")
    public ResponseEntity<SendView> start(CurrentUserArg.Holder cu, @RequestBody(required = false) StartRequest body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(welcome.startAll(cu.user(), body));
    }

    @GetMapping("/sends")
    public PageResponse<SendView> sends(CurrentUserArg.Holder cu, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return welcome.sends(page, size);
    }

    @GetMapping("/sends/{id}")
    public SendView send(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return welcome.send(id);
    }

    @GetMapping("/sends/{id}/deliveries")
    public PageResponse<DeliveryView> deliveries(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return welcome.deliveries(id, status, page, size);
    }

    @GetMapping("/sends/{id}/failed.csv")
    public ResponseEntity<byte[]> failed(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return ImportController.csv("welcome-not-delivered.csv", welcome.failedCsv(id));
    }
}
