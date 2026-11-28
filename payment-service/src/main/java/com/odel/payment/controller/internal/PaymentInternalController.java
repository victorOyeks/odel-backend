package com.odel.payment.controller.internal;

import com.odel.common.tenancy.TenantContext;
import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.service.InternalService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Called by other services only (X-Internal-Key; the university in X-Odel-Tenant-Id): registration (fee block), dashboards. */
@RestController
@RequestMapping("/internal/payments")
public class PaymentInternalController {
    private final InternalService internal;

    public PaymentInternalController(InternalService internal) {
        this.internal = internal;
    }

    @GetMapping("/fee-status")
    public FeeStatus feeStatus(@RequestParam(required = false) UUID studentId, @RequestParam(required = false) UUID userId,
                               @RequestParam(required = false) UUID semesterId) {
        TenantContext.require();
        return internal.feeStatus(studentId, userId, semesterId);
    }

    @PostMapping("/fee-statuses")
    public Items<FeeStatus> feeStatuses(@RequestBody FeeStatusesRequest body) {
        TenantContext.require();
        return internal.feeStatuses(body);
    }

    @GetMapping("/summary")
    public InternalSummary summary(@RequestParam(required = false) UUID semesterId) {
        TenantContext.require();
        return internal.summary(semesterId);
    }
}
