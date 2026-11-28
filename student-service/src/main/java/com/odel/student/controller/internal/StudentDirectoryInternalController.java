package com.odel.student.controller.internal;

import com.odel.common.tenancy.TenantContext;
import com.odel.student.dto.DirectoryDtos.Directory;
import com.odel.student.dto.DirectoryDtos.Entry;
import com.odel.student.service.DirectoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Called by other services only (X-Internal-Key; the university in X-Odel-Tenant-Id): payment-service (B2). */
@RestController
@RequestMapping("/internal/students/directory")
public class StudentDirectoryInternalController {
    private final DirectoryService directory;

    public StudentDirectoryInternalController(DirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping
    public Directory all() {
        TenantContext.require();
        return directory.all();
    }

    @GetMapping("/{studentId}")
    public Entry one(@PathVariable UUID studentId) {
        TenantContext.require();
        return directory.one(studentId);
    }

    @GetMapping("/by-user/{userId}")
    public Entry byUser(@PathVariable UUID userId) {
        TenantContext.require();
        return directory.byUser(userId);
    }
}
