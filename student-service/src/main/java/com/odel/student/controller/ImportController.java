package com.odel.student.controller;

import com.odel.common.PageResponse;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.student.dto.ImportDtos.*;
import com.odel.student.service.ImportValidator;
import com.odel.student.service.StudentImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** W-18 Import admitted students, W-19 Check before importing, W-18b Import history. */
@RestController
@RequestMapping("/api/v1/imports/students")
public class ImportController {
    private final StudentImportService imports;

    public ImportController(StudentImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/template.csv")
    public ResponseEntity<byte[]> template(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return csv("students-template.csv", ImportValidator.template());
    }

    @PostMapping("/uploads")
    public UploadLink upload(CurrentUserArg.Holder cu, @RequestBody UploadRequest body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return imports.uploadLink(body);
    }

    @PostMapping("/validate")
    public Report validate(CurrentUserArg.Holder cu, @RequestBody ValidateRequest body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return imports.validate(cu.user(), body);
    }

    @GetMapping
    public PageResponse<HistoryItem> history(CurrentUserArg.Holder cu, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return imports.history(page, size);
    }

    @GetMapping("/{id}")
    public Report report(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return imports.report(id);
    }

    @GetMapping("/{id}/problems")
    public PageResponse<ProblemView> problems(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestParam(required = false) String severity,
                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return imports.problems(id, severity, page, size);
    }

    @GetMapping("/{id}/problems.csv")
    public ResponseEntity<byte[]> problemsCsv(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return csv("problem-rows.csv", imports.problemsCsv(id));
    }

    @PostMapping("/{id}/confirm")
    public ConfirmResult confirm(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return imports.confirm(cu.user(), id);
    }

    static ResponseEntity<byte[]> csv(String name, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(("﻿" + body).getBytes(StandardCharsets.UTF_8));
    }
}
