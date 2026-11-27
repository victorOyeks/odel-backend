package com.odel.academic.controller;

import com.odel.academic.dto.ImportDtos.*;
import com.odel.academic.service.ImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.odel.academic.client.IdentityClient;
import com.odel.common.security.CurrentUserArg;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Uploads and the shared steps of the structure and catalogue imports (W-12c, W-13c). */
@RestController
@RequestMapping("/api/v1/academic")
public class ImportController {
    private final ImportService imports;
    private final IdentityClient identity;

    public ImportController(ImportService imports, IdentityClient identity) {
        this.imports = imports;
        this.identity = identity;
    }

    @PostMapping("/uploads")
    public UploadResponse upload(CurrentUserArg.Holder cu, @RequestBody UploadRequest body) {
        Guard.write(cu.user());
        return imports.upload(body);
    }

    @GetMapping("/imports/{id}")
    public ImportView get(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.read(cu.user());
        return imports.get(id);
    }

    @PostMapping("/imports/{id}/confirm")
    public ImportView confirm(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.write(cu.user());
        return imports.confirm(id, identity.actor(cu.user()));
    }

    @PostMapping("/imports/{id}/cancel")
    public ResponseEntity<Void> cancel(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.write(cu.user());
        imports.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/imports/{id}/problems.csv")
    public ResponseEntity<String> problems(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.read(cu.user());
        return csv("problem-rows.csv", imports.problemCsv(id));
    }

    @GetMapping("/imports/templates/{kind}.csv")
    public ResponseEntity<String> template(CurrentUserArg.Holder cu, @PathVariable String kind) {
        Guard.read(cu.user());
        return csv(kind + "-template.csv", ImportService.template(kind));
    }

    static ResponseEntity<String> csv(String name, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(body);
    }
}
