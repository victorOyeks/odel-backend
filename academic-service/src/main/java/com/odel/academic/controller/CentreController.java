package com.odel.academic.controller;

import com.odel.academic.dto.CentreDtos.*;
import com.odel.academic.service.CentreService;
import com.odel.academic.client.IdentityClient;
import com.odel.common.security.CurrentUserArg;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** W-14 … W-14d Study centres and rooms. */
@RestController
@RequestMapping("/api/v1/academic")
public class CentreController {
    private final CentreService centres;
    private final IdentityClient identity;

    public CentreController(CentreService centres, IdentityClient identity) {
        this.centres = centres;
        this.identity = identity;
    }

    @GetMapping("/centres")
    public Map<String, List<CentreView>> list(CurrentUserArg.Holder cu) {
        Guard.staff(cu.user());
        return Map.of("items", centres.list());
    }

    @GetMapping("/centres/{id}")
    public CentreView get(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.staff(cu.user());
        return centres.get(id);
    }

    @PostMapping("/centres")
    public ResponseEntity<CentreView> create(CurrentUserArg.Holder cu, @RequestBody CentreIn body) {
        Guard.write(cu.user());
        return ResponseEntity.status(HttpStatus.CREATED).body(centres.save(null, body, identity.actor(cu.user())));
    }

    @PutMapping("/centres/{id}")
    public CentreView update(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody CentreIn body) {
        Guard.write(cu.user());
        return centres.save(id, body, identity.actor(cu.user()));
    }

    @PostMapping("/centres/{id}/rooms")
    public ResponseEntity<RoomView> addRoom(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody RoomIn body) {
        Guard.write(cu.user());
        return ResponseEntity.status(HttpStatus.CREATED).body(centres.addRoom(id, body, identity.actor(cu.user())));
    }

    @PutMapping("/rooms/{id}")
    public RoomView updateRoom(CurrentUserArg.Holder cu, @PathVariable UUID id, @RequestBody RoomIn body) {
        Guard.write(cu.user());
        return centres.updateRoom(id, body, identity.actor(cu.user()));
    }

    @DeleteMapping("/rooms/{id}")
    public ResponseEntity<Void> deleteRoom(CurrentUserArg.Holder cu, @PathVariable UUID id) {
        Guard.write(cu.user());
        centres.deleteRoom(id, identity.actor(cu.user()));
        return ResponseEntity.noContent().build();
    }
}
