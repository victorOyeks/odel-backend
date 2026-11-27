package com.odel.student.controller;

import com.odel.common.security.CurrentUserArg;
import com.odel.student.dto.StudentDtos.Profile;
import com.odel.student.dto.StudentDtos.ProfileUpdate;
import com.odel.student.service.StudentService;
import org.springframework.web.bind.annotation.*;

/** M-06 Check your details: the signed-in student's own record. */
@RestController
@RequestMapping("/api/v1/student-profile")
public class StudentProfileController {
    private final StudentService students;

    public StudentProfileController(StudentService students) {
        this.students = students;
    }

    @GetMapping
    public Profile get(CurrentUserArg.Holder cu) {
        return students.profile(cu.user());
    }

    @PatchMapping
    public Profile update(CurrentUserArg.Holder cu, @RequestBody ProfileUpdate body) {
        return students.updateProfile(cu.user(), body);
    }
}
