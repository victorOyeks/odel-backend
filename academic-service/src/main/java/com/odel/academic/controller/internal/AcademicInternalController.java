package com.odel.academic.controller.internal;

import com.odel.academic.dto.InternalDtos.*;
import com.odel.academic.service.InternalService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Called by other services only (X-Internal-Key); the university comes in X-Odel-Tenant-Id. */
@RestController
@RequestMapping("/internal/academic")
public class AcademicInternalController {
    private final InternalService internal;

    public AcademicInternalController(InternalService internal) {
        this.internal = internal;
    }

    /** Programmes, centres and sessions, for the students import and the student record. */
    @GetMapping("/reference")
    public Reference reference() {
        TenantContext.require();
        return internal.reference();
    }

    @GetMapping("/scopes/{scopeType}/{id}")
    public Scope scope(@PathVariable String scopeType, @PathVariable UUID id) {
        TenantContext.require();
        return internal.scope(scopeType, id);
    }

    @GetMapping("/scopes/resolve")
    public Scope resolve(@RequestParam String scopeType, @RequestParam String code) {
        TenantContext.require();
        return internal.resolve(scopeType, code);
    }

    @GetMapping("/staffing/users/{userId}")
    public Places staffing(@PathVariable UUID userId) {
        TenantContext.require();
        return internal.places(userId);
    }
}
