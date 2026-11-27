package com.odel.academic.client;

import com.odel.academic.dto.PeerDtos.StudentsSummary;
import com.odel.common.ApiException;
import com.odel.common.internal.InternalClient;
import org.springframework.stereotype.Component;

/** The students service's numbers (the other half of B1). Until it answers, every number is zero. */
@Component
public class StudentsClient {
    private final InternalClient internal;

    public StudentsClient(InternalClient internal) {
        this.internal = internal;
    }

    public StudentsSummary summary() {
        try {
            StudentsSummary s = internal.get(InternalClient.Service.STUDENTS, "/internal/students/summary", StudentsSummary.class);
            return s == null ? StudentsSummary.NONE : s;
        } catch (ApiException e) {
            return StudentsSummary.NONE;
        }
    }
}
