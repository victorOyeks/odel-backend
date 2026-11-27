package com.odel.common.tenancy;

import java.util.UUID;

/**
 * The university (or the provider console) the browser is on. The api-gateway works this out from the
 * browser's host and passes it on as headers; services trust them because only the gateway can reach them.
 */
public record ResolvedHost(String host, UUID tenantId, String kind) {
    public static final String REQUEST_ATTRIBUTE = "odel.resolvedHost";

    public boolean known() { return tenantId != null; }
    public boolean isPlatform() { return "PLATFORM".equals(kind); }
    public boolean isUniversity() { return "UNIVERSITY".equals(kind); }
}
