package com.odel.identity.dto;

/**
 * Who Google or Microsoft says the person is, after the ID token was verified. emailVerified: the provider vouches
 * for the address (Google's email_verified; for Microsoft, an address on a domain the person's organisation has proved
 * it owns).
 */
public record SignInIdentity(String email, boolean emailVerified, String name) {
    public String emailDomain() {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).toLowerCase();
    }
}
