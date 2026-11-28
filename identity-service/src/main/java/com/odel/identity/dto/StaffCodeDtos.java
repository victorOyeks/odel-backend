package com.odel.identity.dto;

/** Staff sign-in by emailed code (/api/v1/auth/email-code). */
public final class StaffCodeDtos {
    private StaffCodeDtos() {}

    public record StartRequest(String email, String returnTo) {}

    public record FlowRequest(String flowId) {}

    public record VerifyRequest(String flowId, String code) {}

    /**
     * The same body whether or not the address is a staff account. email: the address as typed (lower case);
     * codeSent: false when a code sent less than a minute ago still works.
     */
    public record CodeSent(String flowId, String email, boolean codeSent, long expiresInSeconds, long resendInSeconds, int sendsLeftThisHour) {}

    /** Where the browser goes next: /auth/continue?next=… (the two-step). */
    public record Next(String next) {}
}
