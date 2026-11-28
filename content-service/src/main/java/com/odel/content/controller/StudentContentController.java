package com.odel.content.controller;

import com.odel.common.security.CurrentUserArg;
import com.odel.content.dto.ContentDtos.*;
import com.odel.content.service.StudentContentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** The student's courses (L-03 … L-13). Active role STUDENT; only courses they are registered for this semester. */
@RestController
@RequestMapping("/api/v1/content/me")
public class StudentContentController {
    private final StudentContentService student;

    public StudentContentController(StudentContentService student) {
        this.student = student;
    }

    @GetMapping("/courses")
    public StudentCourses courses(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId) {
        return student.courses(cu.user(), semesterId);
    }

    @GetMapping("/offerings/{id}")
    public StudentOffering offering(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return student.offering(cu.user(), id);
    }

    @GetMapping("/offerings/{id}/announcements")
    public AnnouncementList announcements(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return student.announcements(cu.user(), id);
    }

    @GetMapping("/weeks/{id}")
    public StudentWeek week(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return student.week(cu.user(), id);
    }

    @GetMapping("/items/{id}")
    public StudentItemDetail item(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return student.detail(cu.user(), id);
    }

    @GetMapping("/items/{id}/playback")
    public Playback playback(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return student.playback(cu.user(), id);
    }

    @PostMapping("/items/{id}/progress")
    public ProgressResult progress(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody ProgressIn body) {
        return student.beat(cu.user(), id, body);
    }

    @PostMapping("/items/{id}/open")
    public Done open(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return student.opened(cu.user(), id);
    }

    @PostMapping("/items/{id}/done")
    public Done done(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return student.markDone(cu.user(), id);
    }

    @GetMapping("/continue")
    public ResponseEntity<Continue> continueWatching(CurrentUserArg.Holder cu) {
        Continue c = student.continueWatching(cu.user());
        return c == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(c);
    }
}
