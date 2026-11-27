package com.odel.academic.controller;

import com.odel.academic.dto.ChecklistDtos.Checklist;
import com.odel.academic.service.ChecklistService;
import com.odel.common.security.CurrentUserArg;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** W-10 Setup checklist (moved here from tenant-service in B1; same response shape as B0). */
@RestController
@RequestMapping("/api/v1/setup")
public class ChecklistController {
    private final ChecklistService checklist;

    public ChecklistController(ChecklistService checklist) {
        this.checklist = checklist;
    }

    @GetMapping("/checklist")
    public Checklist checklist(CurrentUserArg.Holder cu) {
        Guard.read(cu.user());
        return checklist.checklist();
    }
}
