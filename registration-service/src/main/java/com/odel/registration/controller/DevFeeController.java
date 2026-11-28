package com.odel.registration.controller;

import com.odel.common.ApiException;
import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.common.tenancy.TenantContext;
import com.odel.registration.dto.StaffDtos.FeeOverride;
import com.odel.registration.service.DevFeeOverrides;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Set;

/** Dev profile only: say whether one student has paid, without payment-service (the e2e test and the web app's demos). */
@RestController
@RequestMapping("/api/v1/dev/registration")
@ConditionalOnProperty(name = "odel.registration.dev-endpoints", havingValue = "true")
public class DevFeeController {
    private final DevFeeOverrides overrides;

    public DevFeeController(DevFeeOverrides overrides) {
        this.overrides = overrides;
    }

    @PutMapping("/fee-status")
    public ResponseEntity<Void> set(CurrentUserArg.Holder cu, @RequestBody FeeOverride body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        String status = body.status() == null ? "" : body.status().toUpperCase(Locale.ROOT);
        if (body.studentId() == null || body.semesterId() == null || !Set.of("PAID", "NOT_PAID", "CONFIRMING").contains(status)) {
            throw ApiException.badRequest("invalid", "Send studentId, semesterId and status PAID, NOT_PAID or CONFIRMING.");
        }
        overrides.set(TenantContext.require(), body.studentId(), body.semesterId(), status);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/fee-status")
    public ResponseEntity<Void> clear(CurrentUserArg.Holder cu, @RequestBody FeeOverride body) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
        overrides.clear(TenantContext.require(), body.studentId(), body.semesterId());
        return ResponseEntity.noContent().build();
    }
}
