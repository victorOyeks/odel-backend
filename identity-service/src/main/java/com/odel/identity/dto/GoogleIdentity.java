package com.odel.identity.dto;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

/** What a verified Google id_token tells us. */
public record GoogleIdentity(String email, boolean emailVerified, String hostedDomain, String name) {
    public String emailDomain() {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).toLowerCase();
    }
}
