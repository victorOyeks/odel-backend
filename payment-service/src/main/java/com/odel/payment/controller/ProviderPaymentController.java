package com.odel.payment.controller;

import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.service.ProviderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/** Provider console (SUPER_ADMIN): fee and split per university, its settlement account (Paystack subaccount), the settlement report. */
@RestController
@RequestMapping("/api/v1/platform/payments")
public class ProviderPaymentController {
    private final ProviderService provider;

    public ProviderPaymentController(ProviderService provider) {
        this.provider = provider;
    }

    @GetMapping("/universities")
    public Items<University> universities(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.SUPER_ADMIN);
        return provider.universities();
    }

    @GetMapping("/universities/{tenantId}")
    public University one(CurrentUserArg.Holder cu, @PathVariable UUID tenantId) {
        Access.require(cu.user(), Role.SUPER_ADMIN);
        return provider.one(tenantId);
    }

    @PutMapping("/universities/{tenantId}")
    public University amounts(CurrentUserArg.Holder cu, @PathVariable UUID tenantId, @RequestBody AmountsRequest body) {
        Access.require(cu.user(), Role.SUPER_ADMIN);
        return provider.amounts(cu.user(), tenantId, body);
    }

    @PutMapping("/universities/{tenantId}/settlement-account")
    public University account(CurrentUserArg.Holder cu, @PathVariable UUID tenantId, @RequestBody AccountRequest body) {
        Access.require(cu.user(), Role.SUPER_ADMIN);
        return provider.saveAccount(cu.user(), tenantId, body);
    }

    @GetMapping("/banks")
    public Items<BankView> banks(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.SUPER_ADMIN);
        return provider.banks();
    }

    @PostMapping("/banks/resolve")
    public Resolved resolve(CurrentUserArg.Holder cu, @RequestBody ResolveRequest body) {
        Access.require(cu.user(), Role.SUPER_ADMIN);
        return provider.resolve(cu.user(), body);
    }

    @GetMapping("/settlement")
    public Settlement settlement(CurrentUserArg.Holder cu,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) UUID tenantId,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Access.require(cu.user(), Role.SUPER_ADMIN);
        return provider.settlement(from, to, tenantId, page, size);
    }
}
