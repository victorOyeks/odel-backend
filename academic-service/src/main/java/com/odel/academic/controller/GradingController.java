package com.odel.academic.controller;

import com.odel.academic.dto.GradingDtos.*;
import com.odel.academic.service.GradingService;
import com.odel.academic.client.IdentityClient;
import com.odel.common.security.CurrentUserArg;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** W-17 … W-17e Grading rules. */
@RestController
@RequestMapping("/api/v1/academic/grading")
public class GradingController {
    private final GradingService grading;
    private final IdentityClient identity;

    public GradingController(GradingService grading, IdentityClient identity) {
        this.grading = grading;
        this.identity = identity;
    }

    @GetMapping
    public RulesView get(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID programmeId) {
        Guard.read(cu.user());
        return grading.get(programmeId);
    }

    @PutMapping
    public RulesView saveDefault(CurrentUserArg.Holder cu, @RequestBody RulesIn body) {
        Guard.write(cu.user());
        return grading.save(null, body, identity.actor(cu.user()));
    }

    @PutMapping("/programmes/{programmeId}")
    public RulesView saveProgramme(CurrentUserArg.Holder cu, @PathVariable UUID programmeId, @RequestBody RulesIn body) {
        Guard.write(cu.user());
        return grading.save(programmeId, body, identity.actor(cu.user()));
    }

    @DeleteMapping("/programmes/{programmeId}")
    public ResponseEntity<Void> removeProgramme(CurrentUserArg.Holder cu, @PathVariable UUID programmeId) {
        Guard.write(cu.user());
        grading.removeOverride(programmeId, identity.actor(cu.user()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history")
    public Map<String, List<HistoryRow>> history(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID programmeId) {
        Guard.read(cu.user());
        return Map.of("items", grading.history(programmeId));
    }
}
