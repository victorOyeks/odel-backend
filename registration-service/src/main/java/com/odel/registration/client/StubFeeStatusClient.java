package com.odel.registration.client;

import com.odel.registration.config.RegistrationProperties;
import com.odel.registration.dto.PeerDtos.FeeStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/** Stand-in for payment-service: every student has the same answer (odel.registration.fee-stub-default, PAID by default). */
@Component
@ConditionalOnProperty(name = "odel.registration.fee-check", havingValue = "stub")
public class StubFeeStatusClient implements FeeStatusClient {
    private final String answer;

    public StubFeeStatusClient(RegistrationProperties props) {
        this.answer = props.feeStubDefault().toUpperCase(Locale.ROOT);
    }

    @Override
    public FeeStatus status(UUID studentId, UUID semesterId) {
        return new FeeStatus(studentId, semesterId, answer, "PAID".equals(answer), "PAID".equals(answer) ? "STUB" : null, null);
    }

    @Override
    public Map<UUID, FeeStatus> statuses(UUID semesterId, List<UUID> studentIds) {
        Map<UUID, FeeStatus> out = new HashMap<>();
        studentIds.forEach(id -> out.put(id, status(id, semesterId)));
        return out;
    }
}
