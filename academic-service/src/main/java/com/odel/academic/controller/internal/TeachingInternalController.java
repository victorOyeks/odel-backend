package com.odel.academic.controller.internal;

import com.odel.academic.dto.TeachingDtos.Teaching;
import com.odel.academic.service.TeachingService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** For content-service (B3): who lectures and facilitates each course in a session. Internal key only. */
@RestController
@RequestMapping("/internal/academic")
public class TeachingInternalController {
    private final TeachingService teaching;

    public TeachingInternalController(TeachingService teaching) {
        this.teaching = teaching;
    }

    @GetMapping("/teaching")
    public Teaching teaching(@RequestParam(required = false) UUID sessionId, @RequestParam(required = false) UUID userId) {
        TenantContext.require();
        return teaching.teaching(sessionId, userId);
    }
}
