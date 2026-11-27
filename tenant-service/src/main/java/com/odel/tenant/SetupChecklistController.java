package com.odel.tenant;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * W-10 Setup checklist. B0 has none of the set-up data yet, so every step reads from nothing and starts
 * empty; B1 fills each step from its own records (sessions, structure, catalogue, centres, staff, rules).
 */
@RestController
@RequestMapping("/api/v1/setup")
public class SetupChecklistController {

    public record Step(int number, String key, String title, String summary, String status, String actionLabel,
                       String href, String lockedReason) {}

    private record Def(String key, String title, String summary, String href) {}

    private static final List<Def> STEPS = List.of(
            new Def("SESSION", "Session and semesters", "Session dates and semesters", "/staff/academic-setup/sessions"),
            new Def("STRUCTURE", "Academic structure", "Faculties, departments and programmes", "/staff/academic-setup/structure"),
            new Def("CATALOGUE", "Course catalogue", "Courses for each programme", "/staff/courses"),
            new Def("CENTRES", "Study centres", "Where students study and sit exams", "/staff/centres"),
            new Def("PEOPLE", "People and roles", "Invite lecturers, coordinators and staff", "/staff/people"),
            new Def("STAFFING", "Assign staff to courses and centres", "Who teaches each course and runs each centre", "/staff/academic-setup/staffing"),
            new Def("GRADING", "Grading rules", "Default 5-point scale (30% CA / 70% exam)", "/staff/academic-setup/grading"),
            new Def("IMPORT", "Import students", "From the university portal CSV", "/staff/students/import"),
            new Def("WELCOME", "Send welcome messages", "Email with activation link", "/staff/students/welcome"));

    @GetMapping("/checklist")
    public Map<String, Object> checklist(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        List<Step> steps = new ArrayList<>();
        int done = 0;
        for (int i = 0; i < STEPS.size(); i++) {
            Def d = STEPS.get(i);
            boolean afterSetup = d.key().equals("IMPORT") || d.key().equals("WELCOME");
            boolean earlierDone = done >= (d.key().equals("WELCOME") ? 8 : 7);
            if (afterSetup && !earlierDone) {
                steps.add(new Step(i + 1, d.key(), d.title(), d.summary(), "LOCKED", null, d.href(),
                        d.key().equals("IMPORT") ? "Finish steps 1–7 first" : "Import students first"));
            } else {
                steps.add(new Step(i + 1, d.key(), d.title(), d.summary(), "NOT_STARTED", "Start", d.href(), null));
            }
        }
        return Map.of("steps", steps, "done", done, "total", STEPS.size());
    }
}
