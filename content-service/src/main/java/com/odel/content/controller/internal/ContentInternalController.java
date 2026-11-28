package com.odel.content.controller.internal;

import com.odel.common.tenancy.TenantContext;
import com.odel.content.dto.ContentDtos.StudentSummary;
import com.odel.content.service.StudentContentService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Called by other services only (X-Internal-Key; the university in X-Odel-Tenant-Id). */
@RestController
@RequestMapping("/internal/content")
public class ContentInternalController {
    private final StudentContentService student;

    public ContentInternalController(StudentContentService student) {
        this.student = student;
    }

    /** Home and later phases: a student's progress per course in a semester. */
    @GetMapping("/students/{studentId}/summary")
    public StudentSummary summary(@PathVariable UUID studentId, @RequestParam(required = false) UUID semesterId) {
        TenantContext.require();
        return student.summary(studentId, semesterId);
    }
}
