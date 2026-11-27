package com.odel.academic.controller;

import com.odel.academic.dto.SessionDtos.*;
import com.odel.academic.service.SessionService;
import com.odel.academic.client.IdentityClient;
import com.odel.common.security.CurrentUserArg;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** W-11, W-11b, W-11c Sessions and semesters. */
@RestController
@RequestMapping("/api/v1/academic/sessions")
public class SessionController {
    private final SessionService sessions;
    private final IdentityClient identity;

    public SessionController(SessionService sessions, IdentityClient identity) {
        this.sessions = sessions;
        this.identity = identity;
    }

    @GetMapping
    public Map<String, List<SessionView>> list(CurrentUserArg.Holder cu) {
        Guard.staff(cu.user());
        return Map.of("items", sessions.list());
    }

    @GetMapping("/{id}")
    public SessionView get(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.staff(cu.user());
        return sessions.get(id);
    }

    @PostMapping
    public ResponseEntity<SessionView> create(CurrentUserArg.Holder cu, @RequestBody SessionIn body) {
        Guard.write(cu.user());
        return ResponseEntity.status(HttpStatus.CREATED).body(sessions.create(body, identity.actor(cu.user())));
    }

    @PutMapping("/{id}")
    public SessionView update(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody SessionIn body) {
        Guard.write(cu.user());
        return sessions.update(id, body, identity.actor(cu.user()));
    }

    @PostMapping("/{id}/activate")
    public SessionView activate(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.write(cu.user());
        return sessions.activate(id, identity.actor(cu.user()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.write(cu.user());
        sessions.delete(id, identity.actor(cu.user()));
        return ResponseEntity.noContent().build();
    }
}
