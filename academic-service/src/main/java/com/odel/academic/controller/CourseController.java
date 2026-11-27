package com.odel.academic.controller;

import com.odel.academic.dto.CourseDtos.*;
import com.odel.academic.dto.ImportDtos.CheckRequest;
import com.odel.academic.dto.ImportDtos.ImportView;
import com.odel.academic.entity.ImportJob;
import com.odel.academic.service.CourseService;
import com.odel.academic.service.ImportService;
import com.odel.academic.client.IdentityClient;
import com.odel.common.security.CurrentUserArg;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** W-13 … W-13e Course catalogue, and its CSV import (W-13c). */
@RestController
@RequestMapping("/api/v1/academic/courses")
public class CourseController {
    private final CourseService courses;
    private final ImportService imports;
    private final IdentityClient identity;

    public CourseController(CourseService courses, ImportService imports, IdentityClient identity) {
        this.courses = courses;
        this.imports = imports;
        this.identity = identity;
    }

    @GetMapping
    public CoursePage list(CurrentUserArg.Holder cu, @RequestParam(required = false) String search, @RequestParam(required = false) UUID programmeId,
                           @RequestParam(required = false) Integer level, @RequestParam(required = false) Integer semester,
                           @RequestParam(required = false) String lecturer, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "25") int size) {
        Guard.staff(cu.user());
        return courses.list(search, programmeId, level, semester, lecturer, page, size);
    }

    @GetMapping("/{id}")
    public CourseView get(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.staff(cu.user());
        return courses.get(id);
    }

    @PostMapping
    public ResponseEntity<CourseView> create(CurrentUserArg.Holder cu, @RequestBody CourseIn body) {
        Guard.write(cu.user());
        return ResponseEntity.status(HttpStatus.CREATED).body(courses.save(null, body, identity.actor(cu.user())));
    }

    @PutMapping("/{id}")
    public CourseView update(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody CourseIn body) {
        Guard.write(cu.user());
        return courses.save(id, body, identity.actor(cu.user()));
    }

    @PostMapping("/imports")
    public ImportView checkImport(CurrentUserArg.Holder cu, @RequestBody CheckRequest body) {
        Guard.write(cu.user());
        return imports.check(ImportJob.Kind.CATALOGUE, body, identity.actor(cu.user()));
    }
}
