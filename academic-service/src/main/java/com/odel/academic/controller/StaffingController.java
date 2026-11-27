package com.odel.academic.controller;

import com.odel.academic.dto.StaffingDtos.*;
import com.odel.academic.service.StaffingService;
import com.odel.academic.client.IdentityClient;
import com.odel.common.security.CurrentUserArg;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** W-16 … W-16e Staffing. */
@RestController
@RequestMapping("/api/v1/academic")
public class StaffingController {
    private final StaffingService staffing;
    private final IdentityClient identity;

    public StaffingController(StaffingService staffing, IdentityClient identity) {
        this.staffing = staffing;
        this.identity = identity;
    }

    @GetMapping("/staffing")
    public View view(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID sessionId) {
        Guard.read(cu.user());
        return staffing.view(sessionId);
    }

    @PutMapping("/staffing/courses/{courseId}")
    public CourseRow course(CurrentUserArg.Holder cu, @PathVariable UUID courseId, @RequestBody CourseStaffIn body) {
        Guard.write(cu.user());
        return staffing.setCourse(courseId, body, identity.actor(cu.user()));
    }

    @PutMapping("/staffing/centres/{centreId}")
    public CentreRow centre(CurrentUserArg.Holder cu, @PathVariable UUID centreId, @RequestBody CentreStaffIn body) {
        Guard.write(cu.user());
        return staffing.setCentre(centreId, body, identity.actor(cu.user()));
    }

    @PostMapping("/staffing/copy")
    public CopyResult copy(CurrentUserArg.Holder cu, @RequestBody(required = false) CopyIn body) {
        Guard.write(cu.user());
        return staffing.copy(body == null ? new CopyIn(null, null) : body, identity.actor(cu.user()));
    }

    @GetMapping(value = "/staff-options", params = "roles")
    public Map<String, Map<String, List<Option>>> optionsByRole(CurrentUserArg.Holder cu, @RequestParam String roles, @RequestParam(required = false) UUID departmentId) {
        Guard.read(cu.user());
        return Map.of("byRole", staffing.optionsByRole(roles, departmentId));
    }

    @GetMapping("/staff-options")
    public Map<String, List<Option>> options(CurrentUserArg.Holder cu, @RequestParam String role, @RequestParam(required = false) UUID departmentId) {
        Guard.read(cu.user());
        return Map.of("items", staffing.options(role, departmentId));
    }
}
