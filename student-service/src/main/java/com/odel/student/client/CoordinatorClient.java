package com.odel.student.client;

import com.odel.common.internal.InternalClient;
import com.odel.student.dto.CentreMoveDtos.Coordinators;
import org.springframework.stereotype.Component;

/** Who coordinates each centre in the current session (academic-service, GET /internal/academic/coordinators). */
@Component
public class CoordinatorClient {
    private final InternalClient internal;

    public CoordinatorClient(InternalClient internal) {
        this.internal = internal;
    }

    public Coordinators current() {
        return internal.get(InternalClient.Service.ACADEMIC, "/internal/academic/coordinators", Coordinators.class);
    }
}
