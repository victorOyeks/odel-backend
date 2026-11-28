package com.odel.registration.controller;

import com.odel.common.tenancy.TenantContext;
import com.odel.registration.dto.RegistrationDtos.Verification;
import com.odel.registration.service.RegistrationService;
import org.springframework.web.bind.annotation.*;

/** Anyone holding a slip can check it is genuine (the code at the slip's foot). No sign-in; the university from the host. */
@RestController
@RequestMapping("/api/v1/public/registration")
public class PublicVerifyController {
    private final RegistrationService registration;

    public PublicVerifyController(RegistrationService registration) {
        this.registration = registration;
    }

    @GetMapping("/verify/{code}")
    public Verification verify(@PathVariable String code) {
        TenantContext.require();
        return registration.verify(code);
    }
}
