package com.odel.registration.client;

import com.odel.common.ApiException;
import com.odel.common.internal.InternalClient;
import com.odel.registration.config.RegistrationProperties;
import com.odel.registration.dto.PeerDtos.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/** Asks payment-service (:9306). No fee record is NOT_PAID; the bulk call is optional (A-07 then shows no fee column). */
@Component
@ConditionalOnProperty(name = "odel.registration.fee-check", havingValue = "http", matchIfMissing = true)
public class HttpFeeStatusClient implements FeeStatusClient {
    private final InternalClient internal;
    private final RegistrationProperties props;

    public HttpFeeStatusClient(InternalClient internal, RegistrationProperties props) {
        this.internal = internal;
        this.props = props;
    }

    @Override
    public FeeStatus status(UUID studentId, UUID semesterId) {
        try {
            FeeStatus s = internal.getAt(props.paymentsUrl(), "/internal/payments/fee-status?studentId=" + studentId + "&semesterId=" + semesterId, FeeStatus.class);
            return s == null ? new FeeStatus(studentId, semesterId, "NOT_PAID", false, null, null) : s;
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) return new FeeStatus(studentId, semesterId, "NOT_PAID", false, null, null);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "fee_unknown", "We could not check your fee just now. Try again in a minute.");
        }
    }

    @Override
    public Map<UUID, FeeStatus> statuses(UUID semesterId, List<UUID> studentIds) {
        try {
            FeeBulk b = internal.postAt(props.paymentsUrl(), "/internal/payments/fee-status/bulk", new FeeBulkRequest(semesterId, studentIds), FeeBulk.class);
            Map<UUID, FeeStatus> out = new HashMap<>();
            if (b != null && b.items() != null) b.items().forEach(s -> out.put(s.studentId(), s));
            return out;
        } catch (ApiException e) {
            return Map.of();
        }
    }
}
