package com.odel.registration.controller.internal;

import com.odel.common.tenancy.TenantContext;
import com.odel.registration.dto.InternalDtos.*;
import com.odel.registration.service.InternalService;
import com.odel.registration.service.RequestService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Called by other services only (X-Internal-Key; the university in X-Odel-Tenant-Id). */
@RestController
@RequestMapping("/internal/registration")
public class RegistrationInternalController {
    private final InternalService internal;
    private final RequestService requests;

    public RegistrationInternalController(InternalService internal, RequestService requests) {
        this.internal = internal;
        this.requests = requests;
    }

    /** Later phases (classes, exams, results): the courses a student is registered for in a semester. */
    @GetMapping("/students/{studentId}/courses")
    public StudentCourses courses(@PathVariable UUID studentId, @RequestParam(required = false) UUID semesterId) {
        TenantContext.require();
        return internal.courses(studentId, semesterId);
    }

    /** content-service (B3): the class list of a course in a semester. */
    @GetMapping("/courses/{courseId}/students")
    public CourseStudents courseStudents(@PathVariable UUID courseId, @RequestParam(required = false) UUID semesterId) {
        TenantContext.require();
        return internal.courseStudents(courseId, semesterId);
    }

    /** Home and dashboards: registered or not, courses and units, open requests. */
    @GetMapping("/students/{studentId}/summary")
    public StudentSummary studentSummary(@PathVariable UUID studentId) {
        TenantContext.require();
        return internal.studentSummary(studentId);
    }

    @GetMapping("/summary")
    public Summary summary(@RequestParam(required = false) UUID semesterId) {
        TenantContext.require();
        return internal.summary(semesterId);
    }

    /** student-service, on a centre move (A-06): the student's open requests and current registration follow the new centre. */
    @PostMapping("/students/{studentId}/centre-moved")
    public CentreMovedResult centreMoved(@PathVariable UUID studentId, @RequestBody CentreMoved body) {
        TenantContext.require();
        return requests.centreMoved(studentId, body);
    }
}
