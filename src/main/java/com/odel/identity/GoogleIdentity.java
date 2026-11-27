package com.odel.identity;

/** What a verified Google id_token tells us. */
public record GoogleIdentity(String email, boolean emailVerified, String hostedDomain, String name) {
    public String emailDomain() {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).toLowerCase();
    }
}
