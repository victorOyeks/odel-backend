package com.odel.academic.controller.internal;

import com.odel.academic.dto.SemesterInternalDtos.Semesters;
import com.odel.academic.service.SemesterDirectory;
import com.odel.common.tenancy.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Called by other services only (X-Internal-Key; the university in X-Odel-Tenant-Id). */
@RestController
@RequestMapping("/internal/academic")
public class SemesterInternalController {
    private final SemesterDirectory semesters;

    public SemesterInternalController(SemesterDirectory semesters) {
        this.semesters = semesters;
    }

    /** Every session's semesters, newest session first, and which semester is current (payment-service's fee per semester). */
    @GetMapping("/semesters")
    public Semesters semesters() {
        TenantContext.require();
        return semesters.all();
    }
}
