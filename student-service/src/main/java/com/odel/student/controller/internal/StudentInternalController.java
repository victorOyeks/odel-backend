package com.odel.student.controller.internal;

import com.odel.common.tenancy.TenantContext;
import com.odel.student.dto.StudentDtos.*;
import com.odel.student.service.StudentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Called by other services only (X-Internal-Key; the university in X-Odel-Tenant-Id). */
@RestController
@RequestMapping("/internal/students")
public class StudentInternalController {
    private final StudentService students;
    private final com.odel.student.service.ProfileService profiles;

    public StudentInternalController(StudentService students, com.odel.student.service.ProfileService profiles) {
        this.students = students;
        this.profiles = profiles;
    }

    /** academic-service, for the setup checklist (steps 8 Import and 9 Welcome). */
    @GetMapping("/summary")
    public Summary summary() {
        TenantContext.require();
        return students.summary();
    }

    /** identity-service: the student's first name and centre (E-03 and the code emails). */
    @GetMapping("/contact")
    public Contact contact(@RequestParam UUID userId) {
        TenantContext.require();
        return students.contact(userId);
    }

    /** registration-service (B2): one student, by id, by account or by matric number, and the whole roster. */
    @GetMapping("/roster")
    public Roster roster() {
        TenantContext.require();
        return students.roster();
    }

    @GetMapping("/by-user/{userId}")
    public StudentRef byUser(@PathVariable UUID userId) {
        TenantContext.require();
        return students.refByUser(userId);
    }

    /** identity-service (B3): the student confirmed a new email with the code sent to it (L-14e). */
    @PostMapping("/by-user/{userId}/email")
    public org.springframework.http.ResponseEntity<Void> emailChanged(@PathVariable UUID userId,
                                                                      @RequestBody com.odel.student.dto.ProfileDtos.EmailChanged body) {
        TenantContext.require();
        profiles.emailChanged(userId, body);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/by-matric")
    public StudentRef byMatric(@RequestParam String matric) {
        TenantContext.require();
        return students.refByMatric(matric);
    }

    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}")
    public StudentRef one(@PathVariable UUID id) {
        TenantContext.require();
        return students.ref(id);
    }

    /** identity-service: the student activated and accepted the privacy notice. */
    @PostMapping("/activated")
    public ResponseEntity<Void> activated(@RequestBody Activated body) {
        TenantContext.require();
        students.activated(body);
        return ResponseEntity.noContent().build();
    }
}
