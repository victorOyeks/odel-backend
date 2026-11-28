package com.odel.student.client;

import com.odel.common.internal.InternalClient;
import com.odel.student.dto.IdentityDtos.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** student-service → identity-service: student accounts, and staff names for the audit trail. */
@Component
public class IdentityClient {
    private static final InternalClient.Service S = InternalClient.Service.IDENTITY;
    private final InternalClient internal;

    public IdentityClient(InternalClient internal) {
        this.internal = internal;
    }

    public List<Created> upsert(List<Account> accounts) {
        if (accounts.isEmpty()) return List.of();
        try {
            return internal.post(S, "/internal/students/accounts", new AccountsRequest(accounts), CreatedResponse.class).accounts();
        } catch (com.odel.common.ApiException e) {
            throw Upstream.relay(e);
        }
    }

    public List<EmailEntry> emailConflicts(List<EmailEntry> entries) {
        if (entries.isEmpty()) return List.of();
        return internal.post(S, "/internal/students/email-conflicts", new EmailCheckRequest(entries), ConflictsResponse.class).conflicts();
    }

    public AccountView account(UUID userId) {
        return internal.get(S, "/internal/students/accounts/" + userId, AccountView.class);
    }

    public AccountView change(UUID userId, ChangeRequest change) {
        try {
            return internal.post(S, "/internal/students/accounts/" + userId + "/change", change, AccountView.class);
        } catch (com.odel.common.ApiException e) {
            throw Upstream.relay(e);
        }
    }

    public void suspend(UUID userId) {
        internal.post(S, "/internal/students/accounts/" + userId + "/suspend", java.util.Map.of(), AccountView.class);
    }

    public void reinstate(UUID userId) {
        internal.post(S, "/internal/students/accounts/" + userId + "/reinstate", java.util.Map.of(), AccountView.class);
    }

    public void passwordReset(UUID userId) {
        try {
            internal.post(S, "/internal/students/accounts/" + userId + "/password-reset", java.util.Map.of(), Void.class);
        } catch (com.odel.common.ApiException e) {
            throw Upstream.relay(e);
        }
    }

    public StaffView staff(UUID userId) {
        return internal.get(S, "/internal/users/" + userId, StaffView.class);
    }

    /** B3: the student's password, before a phone change (L-14b). Wrong → 401 wrong_password / 423 locked, relayed as they are. */
    public void checkPassword(UUID userId, String password) {
        try {
            java.util.Map<String, String> body = new java.util.HashMap<>();
            body.put("password", password);
            internal.post(S, "/internal/students/accounts/" + userId + "/check-password", body, Void.class);
        } catch (com.odel.common.ApiException e) {
            throw Upstream.relay(e);
        }
    }

    /** B3: the ODeL admins, for the bell when a correction or photo is waiting. */
    @SuppressWarnings("unchecked")
    public List<java.util.Map<String, Object>> staffWithRole(String role) {
        List<java.util.Map<String, Object>> out = internal.get(S, "/internal/staff?role=" + role, List.class);
        return out == null ? List.of() : out;
    }
}
