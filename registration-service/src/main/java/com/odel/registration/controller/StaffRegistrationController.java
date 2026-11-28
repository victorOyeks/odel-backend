package com.odel.registration.controller;

import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.registration.dto.RequestDtos.UploadLink;
import com.odel.registration.dto.RequestDtos.UploadRequest;
import com.odel.registration.dto.StaffDtos.*;
import com.odel.registration.service.CarryOverService;
import com.odel.registration.service.ReportService;
import com.odel.registration.service.RulesService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A-07 Registrations and A-07b, A-05 rules, carry-overs, the staff copy of a slip. */
@RestController
@RequestMapping("/api/v1/registration")
public class StaffRegistrationController {
    private final ReportService reports;
    private final RulesService rules;
    private final CarryOverService carryOvers;

    public StaffRegistrationController(ReportService reports, RulesService rules, CarryOverService carryOvers) {
        this.reports = reports;
        this.rules = rules;
        this.carryOvers = carryOvers;
    }

    @GetMapping("/report")
    public Report report(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId, @RequestParam(required = false) UUID centreId,
                         @RequestParam(required = false) UUID programmeId, @RequestParam(required = false) Integer level,
                         @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return reports.report(cu.user(), semesterId, centreId, programmeId, level, status, q, page, size);
    }

    @GetMapping(value = "/report.csv")
    public ResponseEntity<byte[]> csv(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID semesterId, @RequestParam(required = false) UUID centreId,
                                      @RequestParam(required = false) UUID programmeId, @RequestParam(required = false) Integer level,
                                      @RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        byte[] body = reports.csv(cu.user(), semesterId, centreId, programmeId, level, status, q).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"registrations.csv\"").body(body);
    }

    @GetMapping("/students/{studentId}")
    public StudentRegistration student(CurrentUserArg.Holder cu, @PathVariable UUID studentId, @RequestParam(required = false) UUID semesterId) {
        return reports.student(cu.user(), studentId, semesterId);
    }

    @GetMapping("/registrations/{id}/slip.pdf")
    public ResponseEntity<byte[]> slip(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        return Pdf.of(reports.slip(cu.user(), id));
    }

    // ---- A-05 rules ----

    @GetMapping("/rules")
    public Rules rules(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID programmeId, @RequestParam(required = false) UUID semesterId) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return rules.get(programmeId, semesterId);
    }

    @PutMapping("/rules")
    public Rules saveRules(CurrentUserArg.Holder cu, @RequestBody RulesUpdate body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        return rules.put(cu.user(), body);
    }

    @DeleteMapping("/rules/programmes/{programmeId}")
    public ResponseEntity<Void> resetProgramme(CurrentUserArg.Holder cu, @PathVariable UUID programmeId) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        rules.deleteProgramme(cu.user(), programmeId);
        return ResponseEntity.noContent().build();
    }

    // ---- carry-overs ----

    @GetMapping("/carry-overs")
    public CarryOvers carryOvers(CurrentUserArg.Holder cu, @RequestParam(required = false) UUID studentId) {
        return carryOvers.list(cu.user(), studentId);
    }

    @PostMapping("/carry-overs")
    public ResponseEntity<CarryOverView> addCarryOver(CurrentUserArg.Holder cu, @RequestBody CarryOverIn body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(carryOvers.add(cu.user(), body));
    }

    @DeleteMapping("/carry-overs/{id}")
    public ResponseEntity<Void> removeCarryOver(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        carryOvers.remove(cu.user(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/uploads")
    public UploadLink upload(CurrentUserArg.Holder cu, @RequestBody UploadRequest body) {
        return carryOvers.uploadLink(cu.user(), body);
    }

    @PostMapping("/carry-overs/imports")
    public ImportResult importCarryOvers(CurrentUserArg.Holder cu, @RequestBody ImportIn body) {
        return carryOvers.importFile(cu.user(), body);
    }

    @GetMapping("/carry-overs/template.csv")
    public ResponseEntity<byte[]> template(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"carry-overs-template.csv\"")
                .body(CarryOverService.template().getBytes(StandardCharsets.UTF_8));
    }
}
