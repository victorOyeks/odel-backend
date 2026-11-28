package com.odel.student.controller;

import com.odel.common.PageResponse;
import com.odel.common.security.CurrentUserArg;
import com.odel.student.dto.ProfileDtos.*;
import com.odel.student.service.ProfileReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** The ODeL office reviews corrections and photos (B3), and reads a record's change history (W-22b). */
@RestController
@RequestMapping("/api/v1/students")
public class ProfileReviewController {
    private final ProfileReviewService review;

    public ProfileReviewController(ProfileReviewService review) {
        this.review = review;
    }

    @GetMapping("/profile-requests")
    public ReviewPage list(CurrentUserArg.Holder cu, @RequestParam(required = false) String status, @RequestParam(required = false) String kind,
                           @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return review.list(cu.user(), status, kind, q, page, size);
    }

    @GetMapping("/profile-requests/{id}")
    public ReviewDetail one(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return review.one(cu.user(), id);
    }

    @GetMapping("/profile-requests/{id}/proof")
    public Link proof(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return review.proof(cu.user(), id);
    }

    @PostMapping("/profile-requests/{id}/approve")
    public ReviewDetail approve(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody(required = false) DecisionRequest body) {
        return review.approve(cu.user(), id, body == null ? null : body.note());
    }

    @PostMapping("/profile-requests/{id}/decline")
    public ReviewDetail decline(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody(required = false) DecisionRequest body) {
        return review.decline(cu.user(), id, body == null ? null : body.note());
    }

    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/profile-changes")
    public PageResponse<ChangeView> changes(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "25") int size) {
        return review.changes(cu.user(), id, page, size);
    }
}
