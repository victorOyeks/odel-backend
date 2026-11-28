package com.odel.student.client;

import com.odel.common.internal.InternalClient;
import com.odel.student.dto.CentreMoveDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** student-service → registration-service (:9307): a moved student's open requests follow the new centre. */
@Component
public class RegistrationClient {
    private final InternalClient internal;
    private final String base;

    public RegistrationClient(InternalClient internal, @Value("${odel.student.registration-url:http://127.0.0.1:9307}") String base) {
        this.internal = internal;
        this.base = base;
    }

    public CentreMovedResult centreMoved(UUID studentId, CentreMoved body) {
        return internal.postAt(base, "/internal/registration/students/" + studentId + "/centre-moved", body, CentreMovedResult.class);
    }
}
