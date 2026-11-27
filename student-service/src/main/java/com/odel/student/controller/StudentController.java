package com.odel.student.controller;

import com.odel.common.PageResponse;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.student.dto.StudentDtos;
import com.odel.student.dto.StudentDtos.*;
import com.odel.student.service.StudentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** W-22 Students, W-22b the record drawer, W-22c Add a student. */
@RestController
@RequestMapping("/api/v1/students")
public class StudentController {
    private final StudentService students;

    public StudentController(StudentService students) {
        this.students = students;
    }

    @GetMapping
    public PageResponse<ListItem> list(CurrentUserArg.Holder cu, @RequestParam(required = false) String q, @RequestParam(required = false) String programme,
                                       @RequestParam(required = false) Integer level, @RequestParam(required = false) String centre,
                                       @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return students.list(q, programme, level, centre, status, page, size);
    }

    @GetMapping("/filters")
    public Filters filters(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return students.filters();
    }

    @PostMapping
    public ResponseEntity<StudentDtos.Record> add(CurrentUserArg.Holder cu, @RequestBody AddRequest body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return ResponseEntity.status(HttpStatus.CREATED).body(students.add(cu.user(), body));
    }

    @GetMapping("/{id}")
    public StudentDtos.Record record(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return students.record(id);
    }

    @PatchMapping("/{id}")
    public StudentDtos.Record update(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody UpdateRequest body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return students.update(cu.user(), id, body);
    }

    @PostMapping("/{id}/resend-activation")
    public ResponseEntity<Void> resendActivation(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        students.resendActivation(cu.user(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/password-reset")
    public ResponseEntity<Void> passwordReset(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        students.passwordReset(cu.user(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/suspend")
    public StudentDtos.Record suspend(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody(required = false) SuspendRequest body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return students.suspend(cu.user(), id, body == null ? null : body.reason());
    }

    @PostMapping("/{id}/reinstate")
    public StudentDtos.Record reinstate(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return students.reinstate(cu.user(), id);
    }
}
