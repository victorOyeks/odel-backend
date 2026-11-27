package com.odel.identity.controller;

import com.odel.common.PageResponse;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.identity.dto.AuditDtos.Row;
import com.odel.identity.service.AuditLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/** The university's audit log for the ODeL admin and director. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditLogService audit;

    public AuditController(AuditLogService audit) {
        this.audit = audit;
    }

    @GetMapping
    public PageResponse<Row> list(CurrentUserArg.Holder cu, @RequestParam(required = false) String type, @RequestParam(required = false) String action,
                                  @RequestParam(required = false) UUID actorId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
        return audit.list(type, action, actorId, from, to, page, size);
    }
}
