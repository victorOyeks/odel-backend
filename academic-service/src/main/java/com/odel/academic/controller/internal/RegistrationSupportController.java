package com.odel.academic.controller.internal;

import com.odel.academic.dto.RegistrationSupportDtos.*;
import com.odel.academic.service.RegistrationSupportService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** For registration-service (B2): the calendar, what is offered to whom, and who coordinates each centre. Internal key only. */
@RestController
@RequestMapping("/internal/academic")
public class RegistrationSupportController {
    private final RegistrationSupportService support;

    public RegistrationSupportController(RegistrationSupportService support) {
        this.support = support;
    }

    @GetMapping("/calendar")
    public Calendar calendar() {
        TenantContext.require();
        return support.calendar();
    }

    @GetMapping("/semesters/{id}")
    public SemesterInfo semester(@PathVariable UUID id) {
        TenantContext.require();
        return support.semester(id);
    }

    @GetMapping("/offerings")
    public Courses offerings(@RequestParam UUID programmeId, @RequestParam int level, @RequestParam int semester) {
        TenantContext.require();
        return support.offerings(programmeId, level, semester);
    }

    @GetMapping("/courses")
    public Courses courses(@RequestParam(required = false) String ids, @RequestParam(required = false) String code) {
        TenantContext.require();
        List<UUID> list = ids == null || ids.isBlank() ? List.of()
                : Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(UUID::fromString).toList();
        return support.courses(list, code);
    }

    @GetMapping("/coordinators")
    public Coordinators coordinators(@RequestParam(required = false) UUID sessionId) {
        TenantContext.require();
        return support.coordinators(sessionId);
    }
}
