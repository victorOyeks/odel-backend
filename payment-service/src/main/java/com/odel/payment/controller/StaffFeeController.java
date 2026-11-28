package com.odel.payment.controller;

import com.odel.common.PageResponse;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.service.StaffReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/** The university's payments (A-01, A-02) and its read-only fee settings. The split is never shown here. */
@RestController
@RequestMapping("/api/v1/fees")
public class StaffFeeController {
    static final Role[] ROLES = {Role.ODEL_ADMIN, Role.ODEL_DIRECTOR, Role.MANAGEMENT};
    private final StaffReportService reports;

    public StaffFeeController(StaffReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/payments/summary")
    public Summary summary(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId) {
        Access.require(cu.user(), ROLES);
        return reports.summary(semesterId);
    }

    @GetMapping("/payments")
    public PageResponse<Row> list(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId,
                                  @RequestParam(required = false) String q, @RequestParam(required = false) String centre,
                                  @RequestParam(required = false) String status, @RequestParam(required = false) String method,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Access.require(cu.user(), ROLES);
        return reports.list(new StaffReportService.Filter(semesterId, q, centre, status, method, from, to), page, size);
    }

    @GetMapping("/payments/export.csv")
    public ResponseEntity<byte[]> export(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId,
                                         @RequestParam(required = false) String q, @RequestParam(required = false) String centre,
                                         @RequestParam(required = false) String status, @RequestParam(required = false) String method,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Access.require(cu.user(), ROLES);
        String csv = reports.csv(new StaffReportService.Filter(semesterId, q, centre, status, method, from, to));
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("platform-fee-payments.csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/students/{studentId}")
    public StudentRecord student(CurrentUserArg.Holder cu, @PathVariable UUID studentId, @RequestParam(required = false) UUID semesterId) {
        Access.require(cu.user(), ROLES);
        return reports.student(studentId, semesterId);
    }

    @GetMapping("/settings")
    public UniversitySettings settings(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), ROLES);
        return reports.settings();
    }
}
