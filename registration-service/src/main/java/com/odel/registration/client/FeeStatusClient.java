package com.odel.registration.client;

import com.odel.registration.dto.PeerDtos.FeeStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Has the student paid the semester's fee? payment-service answers (GET /internal/payments/fee-status); until it is
 * deployed a stub can answer instead (odel.registration.fee-check = http | stub).
 */
public interface FeeStatusClient {
    /** status PAID, NOT_PAID or CONFIRMING. Throws ApiException 503 when payment-service cannot be reached. */
    FeeStatus status(UUID studentId, UUID semesterId);

    /** Many students at once (A-07); empty when the bulk call is not available. */
    Map<UUID, FeeStatus> statuses(UUID semesterId, List<UUID> studentIds);
}
