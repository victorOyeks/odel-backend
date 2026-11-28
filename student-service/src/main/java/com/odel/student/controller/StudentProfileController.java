package com.odel.student.controller;

import com.odel.common.security.CurrentUserArg;
import com.odel.student.dto.ProfileDtos.*;
import com.odel.student.service.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The signed-in student's own record: M-06 "Check your details" (B1) and My profile L-14 … L-14i (B3).
 * The login email, password, recovery codes and devices are identity-service's /api/v1/me/**.
 */
@RestController
@RequestMapping("/api/v1/student-profile")
public class StudentProfileController {
    private final ProfileService profiles;

    public StudentProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public MyProfile get(CurrentUserArg.Holder cu) {
        return profiles.profile(cu.user());
    }

    @PatchMapping
    public MyProfile update(CurrentUserArg.Holder cu, @RequestBody Map<String, Object> body) {
        return profiles.update(cu.user(), body);
    }

    @PostMapping("/uploads")
    public UploadLink upload(CurrentUserArg.Holder cu, @RequestBody UploadRequest body) {
        return profiles.upload(cu.user(), body);
    }

    @PostMapping("/photo")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView photo(CurrentUserArg.Holder cu, @RequestBody PhotoRequest body) {
        return profiles.photo(cu.user(), body);
    }

    @GetMapping("/correction-fields")
    public List<CorrectionField> correctionFields(CurrentUserArg.Holder cu) {
        return profiles.correctionFields(cu.user());
    }

    @PostMapping("/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView correction(CurrentUserArg.Holder cu, @RequestBody CorrectionRequest body) {
        return profiles.correction(cu.user(), body);
    }

    @GetMapping("/requests")
    public Requests requests(CurrentUserArg.Holder cu) {
        return profiles.requests(cu.user());
    }

    @GetMapping("/requests/{id}")
    public RequestView request(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return profiles.request(cu.user(), id);
    }

    @GetMapping("/requests/{id}/proof")
    public Link proof(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return profiles.proof(cu.user(), id);
    }

    @PostMapping("/requests/{id}/withdraw")
    public RequestView withdraw(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return profiles.withdraw(cu.user(), id);
    }
}
