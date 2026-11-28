package com.odel.registration.service;

import com.odel.common.ApiException;
import com.odel.common.tenancy.TenantContext;
import com.odel.registration.client.FeeStatusClient;
import com.odel.registration.dto.PeerDtos.FeeStatus;
import com.odel.registration.dto.RegistrationDtos.FeeView;
import org.springframework.stereotype.Service;

import java.util.*;

/** The fee block (FR-3.3): registration needs the semester's platform fee paid, unless the university switched the block off. */
@Service
public class FeeService {
    private final FeeStatusClient client;
    private final DevFeeOverrides overrides;
    private final RulesService rules;

    public FeeService(FeeStatusClient client, DevFeeOverrides overrides, RulesService rules) {
        this.client = client;
        this.overrides = overrides;
        this.rules = rules;
    }

    /** Never throws: an unreachable payment-service is UNKNOWN (registration stays blocked). */
    public FeeView fee(UUID studentId, UUID semesterId, String semesterLabel) {
        String label = "Platform fee · " + semesterLabel;
        if (!rules.settings().isBlockRegistration()) return new FeeView(false, "NOT_REQUIRED", false, label, "/payments");
        String status;
        try {
            status = status(studentId, semesterId);
        } catch (ApiException e) {
            status = "UNKNOWN";
        }
        return new FeeView(true, status, "PAID".equals(status), label, "/payments");
    }

    private String status(UUID studentId, UUID semesterId) {
        Optional<String> o = overrides.get(TenantContext.require(), studentId, semesterId);
        if (o.isPresent()) return o.get();
        FeeStatus s = client.status(studentId, semesterId);
        if (s.status() != null) return s.status().toUpperCase(Locale.ROOT);
        return Boolean.TRUE.equals(s.paid()) ? "PAID" : "NOT_PAID";
    }

    /** For A-07: student id → status; empty when nobody can say (the bulk call is missing). Dev overrides win. */
    public Map<UUID, String> statuses(UUID semesterId, List<UUID> studentIds) {
        Map<UUID, String> out = new HashMap<>();
        if (studentIds.isEmpty()) return out;
        Map<UUID, FeeStatus> bulk;
        try {
            bulk = client.statuses(semesterId, studentIds);
        } catch (RuntimeException e) {
            bulk = Map.of();
        }
        UUID tenant = TenantContext.require();
        for (UUID id : studentIds) {
            Optional<String> o = overrides.get(tenant, id, semesterId);
            if (o.isPresent()) out.put(id, o.get());
            else if (bulk.containsKey(id)) {
                FeeStatus s = bulk.get(id);
                out.put(id, s.status() != null ? s.status().toUpperCase(Locale.ROOT) : Boolean.TRUE.equals(s.paid()) ? "PAID" : "NOT_PAID");
            }
        }
        return bulk.isEmpty() && out.size() < studentIds.size() ? Map.of() : out;
    }
}
