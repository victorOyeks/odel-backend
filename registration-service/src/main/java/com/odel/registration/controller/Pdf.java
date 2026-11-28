package com.odel.registration.controller;

import com.odel.registration.service.RegistrationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class Pdf {
    private Pdf() {}

    static ResponseEntity<byte[]> of(RegistrationService.Slip slip) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + slip.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(slip.pdf());
    }
}
