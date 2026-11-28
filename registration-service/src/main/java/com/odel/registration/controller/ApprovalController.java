package com.odel.registration.controller;

import com.odel.common.security.CurrentUserArg;
import com.odel.registration.dto.RequestDtos.*;
import com.odel.registration.service.RequestService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** A-03 … A-03e: the approvals queue and decisions. Coordinators see and decide their own centres only. */
@RestController
@RequestMapping("/api/v1/registration/requests")
public class ApprovalController {
    private final RequestService requests;

    public ApprovalController(RequestService requests) {
        this.requests = requests;
    }

    @GetMapping
    public com.odel.registration.dto.RequestDtos.Queue queue(CurrentUserArg.Holder cu, @RequestParam(required = false) String kind, @RequestParam(required = false) String status,
                       @RequestParam(required = false) UUID centreId, @RequestParam(required = false) Boolean noCoordinator,
                       @RequestParam(required = false) UUID semesterId, @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "25") int size) {
        return requests.queue(cu.user(), kind, status, centreId, noCoordinator, semesterId, page, size);
    }

    @GetMapping("/{id}")
    public RequestDetail detail(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return requests.detail(cu.user(), id);
    }

    @GetMapping("/{id}/attachment")
    public Link attachment(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return requests.attachment(cu.user(), id);
    }

    @PostMapping("/{id}/approve")
    public RequestView approve(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody(required = false) DecisionIn body) {
        return requests.approve(cu.user(), id, body);
    }

    @PostMapping("/{id}/decline")
    public RequestView decline(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody(required = false) DecisionIn body) {
        return requests.decline(cu.user(), id, body);
    }
}
