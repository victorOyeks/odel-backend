package com.odel.identity.controller;

import com.odel.common.security.Access;
import com.odel.common.security.CurrentUserArg;
import com.odel.common.security.Role;
import com.odel.identity.dto.PeopleDtos.*;
import com.odel.identity.service.PeopleService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** W-15, W-15b, W-15c, W-21 People & roles, and the staff two-step reset. Writes: ODeL admin; reads also the director. */
@RestController
@RequestMapping("/api/v1/people")
public class PeopleController {
    private final PeopleService people;

    public PeopleController(PeopleService people) {
        this.people = people;
    }

    static void read(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.ODEL_ADMIN, Role.ODEL_DIRECTOR);
    }

    static void write(CurrentUserArg.Holder cu) {
        Access.require(cu.user(), Role.ODEL_ADMIN);
    }

    @GetMapping
    public PeoplePage list(CurrentUserArg.Holder cu, @RequestParam(required = false) String search, @RequestParam(required = false) String role,
                           @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "25") int size) {
        read(cu);
        return people.list(search, role, status, page, size);
    }

    @GetMapping("/{id}")
    public PersonDetail get(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        read(cu);
        return people.get(id);
    }

    @PostMapping
    public ResponseEntity<PersonView> invite(CurrentUserArg.Holder cu, @RequestBody InviteRequest body) {
        write(cu);
        return ResponseEntity.status(HttpStatus.CREATED).body(people.invite(body, cu.user()));
    }

    @PostMapping("/uploads")
    public UploadResponse upload(CurrentUserArg.Holder cu, @RequestBody UploadRequest body) {
        write(cu);
        return people.upload(body);
    }

    @GetMapping("/imports/template.csv")
    public ResponseEntity<String> template(CurrentUserArg.Holder cu) {
        read(cu);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"staff-invite-template.csv\"").body(PeopleService.template());
    }

    @PostMapping("/imports")
    public ImportCheck check(CurrentUserArg.Holder cu, @RequestBody CheckRequest body) {
        write(cu);
        return people.check(body);
    }

    @PostMapping("/imports/{id}/confirm")
    public ImportCheck confirm(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        write(cu);
        return people.confirm(id, cu.user());
    }

    @PutMapping("/{id}/roles")
    public PersonView roles(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody RolesRequest body) {
        write(cu);
        return people.setRoles(id, body, cu.user());
    }

    @GetMapping("/{id}/suspend-check")
    public SuspendCheck suspendCheck(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        write(cu);
        return people.suspendCheck(id);
    }

    @PostMapping("/{id}/suspend")
    public PersonView suspend(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody(required = false) ReasonRequest body) {
        write(cu);
        return people.suspend(id, body, cu.user());
    }

    @PostMapping("/{id}/reinstate")
    public PersonView reinstate(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        write(cu);
        return people.reinstate(id, cu.user());
    }

    @PostMapping("/{id}/resend-invitation")
    public ResponseEntity<Void> resend(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        write(cu);
        people.resendInvitation(id, cu.user());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-two-step")
    public ResponseEntity<Void> resetTwoStep(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody(required = false) ReasonRequest body) {
        write(cu);
        people.resetTwoStep(id, body, cu.user());
        return ResponseEntity.noContent().build();
    }
}
