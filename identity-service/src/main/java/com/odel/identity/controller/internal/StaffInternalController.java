package com.odel.identity.controller.internal;

import com.odel.common.tenancy.TenantContext;
import com.odel.identity.dto.StaffDtos.StaffMember;
import com.odel.identity.dto.StaffDtos.StaffSummary;
import com.odel.identity.service.PeopleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Staff for other services (academic-service's staffing and checklist). X-Internal-Key; university in X-Odel-Tenant-Id. */
@RestController
@RequestMapping("/internal/staff")
public class StaffInternalController {
    private final PeopleService people;

    public StaffInternalController(PeopleService people) {
        this.people = people;
    }

    @GetMapping
    public List<StaffMember> staff(@RequestParam(required = false) String role) {
        TenantContext.require();
        return people.staffList(role == null || role.isBlank() ? null : role);
    }

    @GetMapping("/summary")
    public StaffSummary summary() {
        TenantContext.require();
        return people.summary();
    }
}
